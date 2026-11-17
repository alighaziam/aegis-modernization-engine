package io.aegis.planning;

import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.parser.sql.DatabaseDialect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Produces read-only validation SQL for comparing a source database with a target database. */
public final class DataReconciliationPlanner {
    public Plan create(ProjectKnowledgeModel model, String sourceStoreId, String targetStoreId,
                       MappingDocument mappingDocument) {
        if (model == null) throw new IllegalArgumentException("UPKM is required");
        ProjectKnowledgeModel.DataStore sourceStore = store(model, sourceStoreId);
        ProjectKnowledgeModel.DataStore targetStore = store(model, targetStoreId);
        DatabaseDialect sourceDialect = DatabaseDialect.from(sourceStore.vendor());
        DatabaseDialect targetDialect = DatabaseDialect.from(targetStore.vendor());
        if (sourceDialect == DatabaseDialect.UNKNOWN || targetDialect == DatabaseDialect.UNKNOWN)
            throw new IllegalArgumentException("Both database vendors must map to a supported SQL dialect");

        List<String> diagnostics = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        List<ResolvedMapping> mappings = resolveMappings(sourceStore, targetStore, mappingDocument, diagnostics);

        for (ResolvedMapping mapping : mappings) {
            String sourceTable = qualified(mapping.sourceSchema(), mapping.source().name(), sourceDialect);
            String targetTable = qualified(mapping.targetSchema(), mapping.target().name(), targetDialect);
            checks.add(new Check(id(mapping, "row-count"), "ROW_COUNT",
                    "SELECT COUNT(*) AS row_count FROM " + sourceTable,
                    "SELECT COUNT(*) AS row_count FROM " + targetTable,
                    "Source and target row counts should match after the migration batch.",
                    "HIGH", mapping.source().name()));

            Map<String, String> columns = mapping.columns();
            for (var entry : columns.entrySet()) {
                ProjectKnowledgeModel.DbColumn sourceColumn = column(mapping.source(), entry.getKey());
                ProjectKnowledgeModel.DbColumn targetColumn = column(mapping.target(), entry.getValue());
                if (sourceColumn == null || targetColumn == null) continue;
                if (!sameType(sourceColumn.dataType(), targetColumn.dataType()))
                    diagnostics.add("COLUMN_TYPE_DIFFERENCE: " + mapping.source().name() + "."
                            + sourceColumn.name() + " (" + sourceColumn.dataType() + ") -> "
                            + mapping.target().name() + "." + targetColumn.name() + " ("
                            + targetColumn.dataType() + "); verify precision, scale and conversion semantics.");
                if (!targetColumn.nullable()) {
                    checks.add(new Check(id(mapping, "required-" + entry.getValue()), "REQUIRED_COLUMN_NULLS",
                            "SELECT COUNT(*) AS null_count FROM " + sourceTable + " WHERE "
                                    + quote(entry.getKey(), sourceDialect) + " IS NULL",
                            "SELECT COUNT(*) AS null_count FROM " + targetTable + " WHERE "
                                    + quote(entry.getValue(), targetDialect) + " IS NULL",
                            "Required target column should contain zero null values.",
                            "HIGH", mapping.source().name() + "." + entry.getKey()));
                }
            }

            List<ProjectKnowledgeModel.DbConstraint> primaryKeys = mapping.source().constraints().stream()
                    .filter(c -> c.type() == ProjectKnowledgeModel.ConstraintType.PRIMARY_KEY).toList();
            if (primaryKeys.isEmpty()) {
                diagnostics.add("PRIMARY_KEY_NOT_DISCOVERED: " + mapping.source().name()
                        + " has no declared primary key; duplicate-key validation cannot be generated.");
            }
            for (ProjectKnowledgeModel.DbConstraint primaryKey : primaryKeys) {
                List<String> targetKey = primaryKey.columns().stream().map(column -> mappedColumn(columns, column)).toList();
                if (targetKey.stream().anyMatch(value -> value == null || value.isBlank())) {
                    diagnostics.add("PRIMARY_KEY_MAPPING_MISSING: " + mapping.source().name()
                            + " primary key columns are not completely mapped.");
                    continue;
                }
                String sourceKeySql = primaryKey.columns().stream().map(c -> quote(c, sourceDialect))
                        .collect(java.util.stream.Collectors.joining(", "));
                String targetKeySql = targetKey.stream().map(c -> quote(c, targetDialect))
                        .collect(java.util.stream.Collectors.joining(", "));
                boolean targetPrimaryKeyExists = mapping.target().constraints().stream()
                        .filter(c -> c.type() == ProjectKnowledgeModel.ConstraintType.PRIMARY_KEY)
                        .anyMatch(c -> sameColumns(c.columns(), targetKey));
                if (!targetPrimaryKeyExists)
                    diagnostics.add("TARGET_PRIMARY_KEY_NOT_DECLARED: " + mapping.target().name()
                            + " does not declare the mapped source primary key columns " + targetKey + ".");
                checks.add(new Check(id(mapping, "duplicate-pk-" + primaryKey.name()), "DUPLICATE_PRIMARY_KEYS",
                        "SELECT COUNT(*) AS duplicate_groups FROM (SELECT " + sourceKeySql + " FROM " + sourceTable
                                + " GROUP BY " + sourceKeySql + " HAVING COUNT(*) > 1) duplicate_keys",
                        "SELECT COUNT(*) AS duplicate_groups FROM (SELECT " + targetKeySql + " FROM " + targetTable
                                + " GROUP BY " + targetKeySql + " HAVING COUNT(*) > 1) duplicate_keys",
                        "Both source and target should have zero duplicate primary-key groups.",
                        "CRITICAL", mapping.source().name()));
                checks.add(new Check(id(mapping, "null-pk-" + primaryKey.name()), "NULL_PRIMARY_KEYS",
                        "SELECT COUNT(*) AS null_key_rows FROM " + sourceTable + " WHERE "
                                + primaryKey.columns().stream().map(c -> quote(c, sourceDialect) + " IS NULL")
                                .collect(java.util.stream.Collectors.joining(" OR ")),
                        "SELECT COUNT(*) AS null_key_rows FROM " + targetTable + " WHERE "
                                + targetKey.stream().map(c -> quote(c, targetDialect) + " IS NULL")
                                .collect(java.util.stream.Collectors.joining(" OR ")),
                        "Both source and target should have zero rows with null primary-key values.",
                        "CRITICAL", mapping.source().name()));
            }
        }

        for (ResolvedMapping mapping : mappings) {
            for (ProjectKnowledgeModel.DbConstraint foreignKey : mapping.source().constraints()) {
                if (foreignKey.type() != ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY) continue;
                List<ResolvedMapping> parentCandidates = mappings.stream()
                        .filter(candidate -> same(candidate.source().name(), foreignKey.referencedTable())).toList();
                if (parentCandidates.size() != 1) {
                    diagnostics.add("FOREIGN_KEY_PARENT_MAPPING_UNRESOLVED: " + mapping.source().name() + "."
                            + foreignKey.name() + " references " + foreignKey.referencedTable()
                            + " (mapping matches=" + parentCandidates.size() + ").");
                    continue;
                }
                ResolvedMapping parent = parentCandidates.getFirst();
                List<String> childTargetColumns = foreignKey.columns().stream().map(column -> mappedColumn(mapping.columns(), column)).toList();
                List<String> parentTargetColumns = foreignKey.referencedColumns().stream()
                        .map(column -> mappedColumn(parent.columns(), column)).toList();
                if (childTargetColumns.stream().anyMatch(v -> v == null || v.isBlank())
                        || parentTargetColumns.stream().anyMatch(v -> v == null || v.isBlank())) {
                    diagnostics.add("FOREIGN_KEY_COLUMN_MAPPING_MISSING: " + mapping.source().name() + "."
                            + foreignKey.name() + " cannot be reconciled because one or more FK columns are unmapped.");
                    continue;
                }
                boolean targetForeignKeyExists = mapping.target().constraints().stream()
                        .filter(c -> c.type() == ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY)
                        .anyMatch(c -> same(c.referencedTable(), parent.target().name())
                                && sameColumns(c.columns(), childTargetColumns)
                                && sameColumns(c.referencedColumns(), parentTargetColumns));
                if (!targetForeignKeyExists)
                    diagnostics.add("TARGET_FOREIGN_KEY_NOT_DECLARED: " + mapping.target().name() + "."
                            + foreignKey.name() + " has no matching target foreign-key constraint.");
                checks.add(new Check(id(mapping, "orphan-" + foreignKey.name()), "ORPHAN_FOREIGN_KEYS",
                        orphanSql(mapping, parent, foreignKey.columns(), foreignKey.referencedColumns(),
                                sourceDialect, false),
                        orphanSql(mapping, parent, childTargetColumns, parentTargetColumns, targetDialect, true),
                        "Both source and target should have zero orphan foreign-key rows.",
                        "HIGH", mapping.source().name() + "." + foreignKey.name()));
            }
        }

        checks.sort(Comparator.comparing(Check::table).thenComparing(Check::type).thenComparing(Check::id));
        return new Plan(sourceStore.id(), sourceStore.vendor(), targetStore.id(), targetStore.vendor(),
                mappings.size(), checks, diagnostics,
                List.of("The planner emits validation SQL only; it never connects to or executes against either database.",
                        "Automatic mappings require exact case-insensitive table and column names; use a mapping document for renames.",
                        "Type differences are diagnostic and do not imply safe or unsafe conversion without precision/scale review.",
                        "Only declared primary keys and foreign keys are used; triggers, generated columns, partitioning, collation and application-level invariants are not inferred.",
                        "Run checks with read-only credentials and compare results before and after each approved migration batch."));
    }

    private static List<ResolvedMapping> resolveMappings(ProjectKnowledgeModel.DataStore source,
                                                          ProjectKnowledgeModel.DataStore target,
                                                          MappingDocument document,
                                                          List<String> diagnostics) {
        List<TableMapping> requested = document == null ? autoMappings(source, target, diagnostics) : document.tables();
        List<ResolvedMapping> resolved = new ArrayList<>();
        for (TableMapping requestedMapping : requested) {
            List<TableRef> sources = findTables(source, requestedMapping.sourceSchema(), requestedMapping.sourceTable());
            List<TableRef> targets = findTables(target, requestedMapping.targetSchema(), requestedMapping.targetTable());
            if (sources.size() != 1 || targets.size() != 1) {
                diagnostics.add("TABLE_MAPPING_UNRESOLVED: " + requestedMapping.sourceTable() + " -> "
                        + requestedMapping.targetTable() + " (source matches=" + sources.size()
                        + ", target matches=" + targets.size() + ").");
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            if (requestedMapping.columns().isEmpty()) {
                for (ProjectKnowledgeModel.DbColumn sourceColumn : sources.getFirst().table().columns()) {
                    if (column(targets.getFirst().table(), sourceColumn.name()) != null)
                        columns.put(sourceColumn.name(), sourceColumn.name());
                }
            } else {
                requestedMapping.columns().forEach(column -> columns.put(column.sourceColumn(), column.targetColumn()));
            }
            Map<String, String> validColumns = new LinkedHashMap<>();
            for (var entry : columns.entrySet()) {
                if (column(sources.getFirst().table(), entry.getKey()) == null
                        || column(targets.getFirst().table(), entry.getValue()) == null) {
                    diagnostics.add("COLUMN_MAPPING_UNRESOLVED: " + requestedMapping.sourceTable() + "."
                            + entry.getKey() + " -> " + requestedMapping.targetTable() + "." + entry.getValue());
                    continue;
                }
                validColumns.put(entry.getKey(), entry.getValue());
            }
            resolved.add(new ResolvedMapping(sources.getFirst().schema(), sources.getFirst().table(),
                    targets.getFirst().schema(), targets.getFirst().table(), validColumns));
        }
        return resolved;
    }

    private static List<TableMapping> autoMappings(ProjectKnowledgeModel.DataStore source,
                                                   ProjectKnowledgeModel.DataStore target,
                                                   List<String> diagnostics) {
        List<TableMapping> mappings = new ArrayList<>();
        for (var schema : source.schemas()) for (var table : schema.tables()) {
            List<TableRef> matches = findTables(target, null, table.name());
            if (matches.size() == 1) mappings.add(new TableMapping(schema.name(), table.name(),
                    matches.getFirst().schema(), matches.getFirst().table().name(), List.of()));
            else diagnostics.add("AUTO_TABLE_MAPPING_UNRESOLVED: " + schema.name() + "." + table.name()
                    + " has " + matches.size() + " target table matches; supply an explicit mapping document.");
        }
        return mappings;
    }

    private static List<TableRef> findTables(ProjectKnowledgeModel.DataStore store, String schema, String table) {
        return store.schemas().stream().filter(s -> blank(schema) || same(s.name(), schema))
                .flatMap(s -> s.tables().stream().filter(t -> same(t.name(), table))
                        .map(t -> new TableRef(s.name(), t)))
                .toList();
    }

    private static ProjectKnowledgeModel.DbColumn column(ProjectKnowledgeModel.DbTable table, String name) {
        return table.columns().stream().filter(c -> same(c.name(), name)).findFirst().orElse(null);
    }

    private static String orphanSql(ResolvedMapping child, ResolvedMapping parent,
                                    List<String> childColumns, List<String> parentColumns,
                                    DatabaseDialect dialect, boolean targetSide) {
        String childSchema = targetSide ? child.targetSchema() : child.sourceSchema();
        ProjectKnowledgeModel.DbTable childModel = targetSide ? child.target() : child.source();
        String parentSchema = targetSide ? parent.targetSchema() : parent.sourceSchema();
        ProjectKnowledgeModel.DbTable parentModel = targetSide ? parent.target() : parent.source();
        String childTable = qualified(childSchema, childModel.name(), dialect);
        String parentTable = qualified(parentSchema, parentModel.name(), dialect);
        List<String> predicates = new ArrayList<>();
        for (int i = 0; i < childColumns.size(); i++)
            predicates.add("p." + quote(parentColumns.get(i), dialect) + " = c." + quote(childColumns.get(i), dialect));
        String notNull = childColumns.stream().map(c -> "c." + quote(c, dialect) + " IS NOT NULL")
                .collect(java.util.stream.Collectors.joining(" AND "));
        return "SELECT COUNT(*) AS orphan_rows FROM " + childTable + " c WHERE " + notNull
                + " AND NOT EXISTS (SELECT 1 FROM " + parentTable + " p WHERE "
                + String.join(" AND ", predicates) + ")";
    }

    private static String mappedColumn(Map<String, String> mappings, String sourceColumn) {
        return mappings.entrySet().stream().filter(entry -> same(entry.getKey(), sourceColumn))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static boolean sameColumns(List<String> left, List<String> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!same(left.get(i), right.get(i))) return false;
        return true;
    }

    private static ProjectKnowledgeModel.DataStore store(ProjectKnowledgeModel model, String id) {
        return model.dataStores().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Data store not found: " + id));
    }

    private static String qualified(String schema, String table, DatabaseDialect dialect) {
        String prefix = blank(schema) ? "" : java.util.Arrays.stream(schema.split("\\."))
                .map(part -> quote(part, dialect)).collect(java.util.stream.Collectors.joining("."));
        return prefix.isBlank() ? quote(table, dialect) : prefix + "." + quote(table, dialect);
    }

    private static String quote(String identifier, DatabaseDialect dialect) {
        return switch (dialect) {
            case MYSQL, MARIADB -> "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER -> "[" + identifier.replace("]", "]]") + "]";
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    private static boolean sameType(String left, String right) {
        return left != null && right != null && left.replaceAll("\\s+", "")
                .equalsIgnoreCase(right.replaceAll("\\s+", ""));
    }
    private static boolean same(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String id(ResolvedMapping mapping, String suffix) {
        return (mapping.sourceSchema() + "-" + mapping.source().name() + "-" + suffix).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
    }

    private record TableRef(String schema, ProjectKnowledgeModel.DbTable table) {}
    private record ResolvedMapping(String sourceSchema, ProjectKnowledgeModel.DbTable source,
                                   String targetSchema, ProjectKnowledgeModel.DbTable target,
                                   Map<String, String> columns) {
        private ResolvedMapping { columns = Map.copyOf(columns); }
    }

    public record ColumnMapping(String sourceColumn, String targetColumn) {}
    public record TableMapping(String sourceSchema, String sourceTable, String targetSchema,
                               String targetTable, List<ColumnMapping> columns) {
        public TableMapping { columns = columns == null ? List.of() : List.copyOf(columns); }
    }
    public record MappingDocument(List<TableMapping> tables) {
        public MappingDocument { tables = tables == null ? List.of() : List.copyOf(tables); }
    }
    public record Check(String id, String type, String sourceSql, String targetSql,
                        String expectedResult, String severity, String table) {}
    public record Plan(String sourceStoreId, String sourceVendor, String targetStoreId, String targetVendor,
                       int mappedTables, List<Check> checks, List<String> diagnostics,
                       List<String> limitations) {
        public Plan {
            checks = List.copyOf(checks);
            diagnostics = List.copyOf(diagnostics);
            limitations = List.copyOf(limitations);
        }
    }
}

package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compares explicit JPA mappings with discovered physical DDL without inventing naming conventions. */
public final class JpaSchemaConsistencyAnalyzer {
    public ConsistencyReport analyze(ProjectKnowledgeModel project,
                                     JpaMappingAnalyzer.MappingReport jpa,
                                     DataLineageAnalyzer.LineageReport physicalLineage) {
        if (project == null || jpa == null || physicalLineage == null)
            throw new IllegalArgumentException("Project, JPA mapping and physical lineage reports are required");
        List<TableRef> tables = project.dataStores().stream()
                .flatMap(store -> store.schemas().stream().flatMap(schema -> schema.tables().stream()
                        .map(table -> new TableRef(store.id(), schema.name(), table))))
                .toList();
        if (tables.isEmpty())
            return new ConsistencyReport(false, 0, 0, 0, 0, List.of(),
                    List.of("Physical schema was not supplied; JPA-to-DDL consistency could not be evaluated."));

        List<ConsistencyFinding> findings = new ArrayList<>();
        Map<String, TableRef> entityTables = new LinkedHashMap<>();
        int matchedEntities = 0;
        int unmatchedEntities = 0;
        int checkedColumns = 0;
        int matchedRelationships = 0;

        for (JpaMappingAnalyzer.EntityMapping entity : jpa.entities()) {
            if (blank(entity.tableName())) {
                unmatchedEntities++;
                findings.add(finding("JPA_IMPLICIT_TABLE_NAME", "WARNING", entity.qualifiedName(), null,
                        "No explicit @Table name is available; the provider naming strategy is not guessed.",
                        entity.sourceLocator()));
                continue;
            }
            List<TableRef> candidates = tables.stream()
                    .filter(ref -> equalsName(ref.table().name(), entity.tableName()))
                    .filter(ref -> blank(entity.schema()) || equalsName(ref.schema(), entity.schema()))
                    .toList();
            if (candidates.isEmpty()) {
                unmatchedEntities++;
                findings.add(finding("JPA_TABLE_NOT_FOUND", "ERROR", entity.qualifiedName(), entity.tableName(),
                        "The explicitly mapped JPA table was not found in the discovered physical schema.",
                        entity.sourceLocator()));
                continue;
            }
            if (candidates.size() > 1) {
                unmatchedEntities++;
                findings.add(finding("JPA_TABLE_AMBIGUOUS", "WARNING", entity.qualifiedName(), entity.tableName(),
                        "Multiple physical tables match this entity mapping; schema/store selection is ambiguous.",
                        entity.sourceLocator()));
                continue;
            }
            TableRef physical = candidates.getFirst();
            entityTables.put(entity.qualifiedName(), physical);
            matchedEntities++;

            for (JpaMappingAnalyzer.ColumnMapping column : entity.columns()) {
                if (blank(column.columnName())) {
                    findings.add(finding("JPA_IMPLICIT_COLUMN_NAME", "WARNING", entity.qualifiedName(),
                            physical.qualifiedName(), "Field " + column.fieldName()
                                    + " has no explicit column name; provider naming rules are not guessed.",
                            entity.sourceLocator()));
                    continue;
                }
                checkedColumns++;
                ProjectKnowledgeModel.DbColumn dbColumn = physical.table().columns().stream()
                        .filter(candidate -> equalsName(candidate.name(), column.columnName()))
                        .findFirst().orElse(null);
                if (dbColumn == null) {
                    findings.add(finding("JPA_COLUMN_NOT_FOUND", "ERROR", entity.qualifiedName(),
                            physical.qualifiedName(), "Mapped column " + column.columnName()
                                    + " was not found in the physical table.", entity.sourceLocator()));
                    continue;
                }
                if (!blank(column.nullable())) {
                    boolean jpaNullable = Boolean.parseBoolean(column.nullable());
                    if (jpaNullable != dbColumn.nullable())
                        findings.add(finding("JPA_NULLABILITY_MISMATCH", "WARNING", entity.qualifiedName(),
                                physical.qualifiedName(), "Column " + column.columnName()
                                        + " has different JPA and physical nullability.", entity.sourceLocator()));
                }
                if ("true".equalsIgnoreCase(column.unique()) && !isUnique(physical.table(), column.columnName()))
                    findings.add(finding("JPA_UNIQUE_NOT_ENFORCED", "WARNING", entity.qualifiedName(),
                            physical.qualifiedName(), "JPA declares " + column.columnName()
                                    + " unique but no matching physical unique constraint/index was found.",
                            entity.sourceLocator()));
            }
        }

        for (JpaMappingAnalyzer.RelationshipEdge relationship : jpa.relationships()) {
            if (!isOwningForeignKeyRelationship(relationship.kind())) continue;
            TableRef source = entityTables.get(relationship.fromEntity());
            TableRef target = entityTables.get(relationship.toEntity());
            if (source == null || target == null) continue;
            List<String> joinColumns = relationship.joinColumns().stream()
                    .map(JpaMappingAnalyzer.ColumnMapping::columnName).filter(value -> !blank(value)).toList();
            if (joinColumns.isEmpty()) continue;
            List<DataLineageAnalyzer.LineageEdge> matching = physicalLineage.edges().stream()
                    .filter(edge -> equalsName(edge.sourceTable(), source.qualifiedName()))
                    .filter(edge -> equalsName(edge.targetTable(), target.qualifiedName()))
                    .filter(edge -> containsAllIgnoreCase(edge.sourceColumns(), joinColumns))
                    .toList();
            if (matching.isEmpty()) {
                findings.add(finding("JPA_RELATIONSHIP_WITHOUT_PHYSICAL_FK", "WARNING",
                        relationship.fromEntity(), source.qualifiedName(),
                        "Owning JPA relationship " + relationship.fieldName()
                                + " has no matching declared physical foreign key to " + target.qualifiedName() + ".",
                        relationship.sourceLocator()));
            } else matchedRelationships++;
        }

        findings.sort(Comparator.comparing(ConsistencyFinding::severity)
                .thenComparing(ConsistencyFinding::entity)
                .thenComparing(ConsistencyFinding::code)
                .thenComparing(ConsistencyFinding::message));
        return new ConsistencyReport(true, matchedEntities, unmatchedEntities, checkedColumns,
                matchedRelationships, findings,
                List.of("Only explicit JPA table/column/join declarations are compared.",
                        "Implicit provider naming strategies, converters, inheritance strategies and secondary tables are not inferred.",
                        "Relationship validation is limited to owning ManyToOne/OneToOne joins with explicit join-column names.",
                        "A warning is not proof of runtime incompatibility; review provider configuration and deployed schema."));
    }

    private static boolean isUnique(ProjectKnowledgeModel.DbTable table, String column) {
        boolean uniqueConstraint = table.constraints().stream()
                .anyMatch(c -> c.type() == ProjectKnowledgeModel.ConstraintType.UNIQUE
                        && containsAllIgnoreCase(c.columns(), List.of(column)));
        boolean uniqueIndex = table.indexes().stream()
                .anyMatch(i -> i.unique() && containsAllIgnoreCase(i.columns(), List.of(column)));
        return uniqueConstraint || uniqueIndex;
    }

    private static boolean isOwningForeignKeyRelationship(String kind) {
        if (kind == null) return false;
        String normalized = kind.toLowerCase(Locale.ROOT);
        return normalized.contains("manytoone") || normalized.contains("onetoone");
    }

    private static boolean containsAllIgnoreCase(List<String> values, List<String> expected) {
        return expected.stream().allMatch(item -> values.stream().anyMatch(value -> equalsName(value, item)));
    }

    private static boolean equalsName(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static ConsistencyFinding finding(String code, String severity, String entity,
                                              String table, String message, String source) {
        return new ConsistencyFinding(code, severity, entity, table, message, source);
    }

    private record TableRef(String store, String schema, ProjectKnowledgeModel.DbTable table) {
        private String qualifiedName() { return store + "." + schema + "." + table.name(); }
    }

    public record ConsistencyFinding(String code, String severity, String entity,
                                     String physicalTable, String message, String sourceLocator) {}
    public record ConsistencyReport(boolean available, int matchedEntities, int unmatchedEntities,
                                    int checkedColumns, int matchedRelationships,
                                    List<ConsistencyFinding> findings, List<String> limitations) {
        public ConsistencyReport {
            findings = List.copyOf(findings);
            limitations = List.copyOf(limitations);
        }
        public long errorCount() { return findings.stream().filter(f -> f.severity().equals("ERROR")).count(); }
        public long warningCount() { return findings.stream().filter(f -> f.severity().equals("WARNING")).count(); }
    }
}

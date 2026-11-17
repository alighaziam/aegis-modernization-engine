package io.aegis.discovery;

import io.aegis.domain.ProjectKnowledgeModel;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Discovers relational schema metadata using JDBC metadata APIs only; no application SQL is executed. */
public final class DatabaseMetadataDiscovery {
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "sys", "system", "syscat", "nullid", "systools", "qsys2");

    public DiscoveryReport discover(Connection connection, String dataStoreId, String schemaPattern)
            throws SQLException {
        if (connection == null) throw new IllegalArgumentException("JDBC connection is required");
        if (dataStoreId == null || dataStoreId.isBlank())
            throw new IllegalArgumentException("dataStoreId is required");

        boolean wasReadOnly = connection.isReadOnly();
        boolean changedReadOnly = false;
        try {
            if (!wasReadOnly) {
                connection.setReadOnly(true);
                changedReadOnly = connection.isReadOnly();
                if (!changedReadOnly)
                    throw new SQLException("JDBC driver did not confirm read-only mode; metadata discovery was stopped");
            }
            DatabaseMetaData metadata = connection.getMetaData();
            String vendor = metadata.getDatabaseProductName();
            String version = metadata.getDatabaseProductVersion();
            List<String> diagnostics = new ArrayList<>();
            List<TableRef> discoveredTables = discoverTables(metadata, schemaPattern);
            if (discoveredTables.isEmpty())
                diagnostics.add("NO_TABLES_DISCOVERED: No user tables matched the supplied schema filter.");

            Map<String, List<ProjectKnowledgeModel.DbTable>> schemas = new LinkedHashMap<>();
            int columnCount = 0;
            int foreignKeyCount = 0;
            for (TableRef table : discoveredTables) {
                String schemaName = table.schemaLabel();
                String locator = locator(vendor, schemaName, table.name());
                List<ProjectKnowledgeModel.DbColumn> columns = readColumns(metadata, table, locator);
                List<ProjectKnowledgeModel.DbConstraint> constraints = new ArrayList<>();
                constraints.addAll(readPrimaryKeys(metadata, table, locator));
                List<ProjectKnowledgeModel.DbConstraint> foreignKeys = readImportedKeys(metadata, table, locator);
                constraints.addAll(foreignKeys);
                List<ProjectKnowledgeModel.DbIndex> indexes = readIndexes(metadata, table, locator);
                columnCount += columns.size();
                foreignKeyCount += foreignKeys.size();
                schemas.computeIfAbsent(schemaName, ignored -> new ArrayList<>())
                        .add(new ProjectKnowledgeModel.DbTable(table.name(), columns, constraints, indexes, locator));
            }

            List<ProjectKnowledgeModel.DbSchema> dbSchemas = schemas.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .map(entry -> new ProjectKnowledgeModel.DbSchema(entry.getKey(), entry.getValue().stream()
                            .sorted(Comparator.comparing(ProjectKnowledgeModel.DbTable::name,
                                    String.CASE_INSENSITIVE_ORDER)).toList()))
                    .toList();
            ProjectKnowledgeModel.DataStore store =
                    new ProjectKnowledgeModel.DataStore(dataStoreId, vendor, version, dbSchemas);
            return new DiscoveryReport(store, discoveredTables.size(), columnCount, foreignKeyCount,
                    diagnostics, List.of("Discovery uses DatabaseMetaData only and never executes source SQL.",
                            "Only TABLE objects are included; views, materialized views, routines, triggers and grants are not modeled.",
                            "Check constraints, generated expressions, partition details and vendor-specific metadata may be omitted.",
                            "The JDBC driver's read-only flag is required but remains a driver-level hint; use a database account with SELECT/catalog-only privileges."));
        } finally {
            if (changedReadOnly) connection.setReadOnly(false);
        }
    }

    private static List<TableRef> discoverTables(DatabaseMetaData metadata, String schemaPattern) throws SQLException {
        List<TableRef> tables = new ArrayList<>();
        try (ResultSet rows = metadata.getTables(null, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rows.next()) {
                TableRef table = new TableRef(rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"),
                        rows.getString("TABLE_NAME"));
                if (!systemSchema(table, schemaPattern)) tables.add(table);
            }
        }
        if (tables.isEmpty()) {
            try (ResultSet catalogs = metadata.getCatalogs()) {
                while (catalogs.next()) {
                    String catalog = catalogs.getString("TABLE_CAT");
                    if (schemaPattern != null && !schemaPattern.equalsIgnoreCase(catalog)) continue;
                    try (ResultSet rows = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
                        while (rows.next()) {
                            TableRef table = new TableRef(rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"),
                                    rows.getString("TABLE_NAME"));
                            if (!systemSchema(table, schemaPattern)) tables.add(table);
                        }
                    }
                }
            }
        }
        return tables.stream().distinct()
                .sorted(Comparator.comparing(TableRef::schemaLabel, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(TableRef::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static boolean systemSchema(TableRef table, String requestedPattern) {
        if (requestedPattern != null) return false;
        String schema = table.schema() == null ? table.catalog() : table.schema();
        return schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT));
    }

    private static List<ProjectKnowledgeModel.DbColumn> readColumns(DatabaseMetaData metadata, TableRef table,
                                                                      String locator) throws SQLException {
        List<ProjectKnowledgeModel.DbColumn> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(table.catalog(), table.schema(), table.name(), "%")) {
            while (rows.next()) {
                int size = rows.getInt("COLUMN_SIZE");
                Integer length = rows.wasNull() ? null : size;
                int scaleValue = rows.getInt("DECIMAL_DIGITS");
                Integer scale = rows.wasNull() ? null : scaleValue;
                int nullable = rows.getInt("NULLABLE");
                columns.add(new ProjectKnowledgeModel.DbColumn(rows.getString("COLUMN_NAME"),
                        rows.getString("TYPE_NAME"), length, null, scale,
                        nullable != DatabaseMetaData.columnNoNulls, rows.getString("COLUMN_DEF"), locator));
            }
        }
        columns.sort(Comparator.comparing(ProjectKnowledgeModel.DbColumn::name, String.CASE_INSENSITIVE_ORDER));
        return columns;
    }

    private static List<ProjectKnowledgeModel.DbConstraint> readPrimaryKeys(DatabaseMetaData metadata,
                                                                              TableRef table, String locator)
            throws SQLException {
        Map<String, List<OrderedColumn>> groups = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getPrimaryKeys(table.catalog(), table.schema(), table.name())) {
            while (rows.next()) {
                String name = rows.getString("PK_NAME");
                if (name == null || name.isBlank()) name = "PK_" + table.name();
                groups.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(new OrderedColumn(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME")));
            }
        }
        return groups.entrySet().stream().map(entry -> new ProjectKnowledgeModel.DbConstraint(
                entry.getKey(), ProjectKnowledgeModel.ConstraintType.PRIMARY_KEY,
                ordered(entry.getValue()), null, List.of(), locator)).toList();
    }

    private static List<ProjectKnowledgeModel.DbConstraint> readImportedKeys(DatabaseMetaData metadata,
                                                                               TableRef table, String locator)
            throws SQLException {
        Map<String, List<ImportedKey>> groups = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getImportedKeys(table.catalog(), table.schema(), table.name())) {
            while (rows.next()) {
                String referencedTable = rows.getString("PKTABLE_NAME");
                String name = rows.getString("FK_NAME");
                if (name == null || name.isBlank()) name = "FK_" + table.name() + "_" + referencedTable;
                String key = name + "|" + referencedTable;
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new ImportedKey(
                        name, referencedTable, rows.getShort("KEY_SEQ"),
                        rows.getString("FKCOLUMN_NAME"), rows.getString("PKCOLUMN_NAME")));
            }
        }
        return groups.values().stream().map(rows -> {
            rows.sort(Comparator.comparingInt(ImportedKey::sequence));
            ImportedKey first = rows.getFirst();
            return new ProjectKnowledgeModel.DbConstraint(first.name(),
                    ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                    rows.stream().map(ImportedKey::sourceColumn).toList(),
                    first.referencedTable(), rows.stream().map(ImportedKey::targetColumn).toList(), locator);
        }).toList();
    }

    private static List<ProjectKnowledgeModel.DbIndex> readIndexes(DatabaseMetaData metadata,
                                                                     TableRef table, String locator)
            throws SQLException {
        Map<String, List<OrderedColumn>> groups = new LinkedHashMap<>();
        Map<String, Boolean> uniqueness = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(table.catalog(), table.schema(), table.name(), true, false)) {
            while (rows.next()) {
                short type = rows.getShort("TYPE");
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (type == DatabaseMetaData.tableIndexStatistic || name == null || column == null) continue;
                groups.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(new OrderedColumn(rows.getShort("ORDINAL_POSITION"), column));
                uniqueness.put(name, !rows.getBoolean("NON_UNIQUE"));
            }
        }
        return groups.entrySet().stream().map(entry -> new ProjectKnowledgeModel.DbIndex(
                entry.getKey(), ordered(entry.getValue()), uniqueness.getOrDefault(entry.getKey(), false), locator)).toList();
    }

    private static List<String> ordered(List<OrderedColumn> columns) {
        return columns.stream().sorted(Comparator.comparingInt(OrderedColumn::sequence))
                .map(OrderedColumn::name).toList();
    }

    private static String locator(String vendor, String schema, String table) {
        return "jdbc://" + safe(vendor) + "/" + safe(schema) + "/" + safe(table);
    }
    private static String safe(String value) { return value == null ? "default" : value.replace("/", "_"); }

    private record TableRef(String catalog, String schema, String name) {
        private String schemaLabel() {
            if (schema != null && catalog != null) return catalog + "." + schema;
            return schema != null ? schema : catalog != null ? catalog : "default";
        }
    }
    private record OrderedColumn(int sequence, String name) {}
    private record ImportedKey(String name, String referencedTable, int sequence,
                               String sourceColumn, String targetColumn) {}

    public record DiscoveryReport(ProjectKnowledgeModel.DataStore dataStore, int tables,
                                  int columns, int foreignKeys, List<String> diagnostics,
                                  List<String> limitations) {
        public DiscoveryReport {
            diagnostics = List.copyOf(diagnostics);
            limitations = List.copyOf(limitations);
        }
    }
}

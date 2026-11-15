package io.aegis.transformation;

import io.aegis.parser.sql.DatabaseDialect;
import io.aegis.parser.sql.SqlDdlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative cross-dialect adapter for the CREATE TABLE/INDEX subset parsed by Aegis.
 * It deliberately omits defaults and vendor-specific semantics that cannot be proven safe.
 */
public final class CommonDdlMigrationAdapter {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    public DatabaseMigrationPlanner.MigrationPlan plan(
            SqlDdlParser.ParseResult parsed, DatabaseDialect target, String requestedSchema) {
        String schema = requestedSchema == null || requestedSchema.isBlank()
                ? defaultSchema(target) : requestedSchema.trim();
        List<String> statements = new ArrayList<>();
        List<DatabaseMigrationPlanner.Diagnostic> diagnostics = new ArrayList<>();

        if (!IDENTIFIER.matcher(schema).matches()) {
            diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                    "UNSAFE_TARGET_SCHEMA", "Target schema must be a simple SQL identifier.", "ERROR"));
            return result(parsed, target, schema, statements, diagnostics);
        }
        parsed.diagnostics().forEach(d -> diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                "SOURCE_" + d.code(), d.message(), "REVIEW_REQUIRED")));

        for (SqlDdlParser.Table table : parsed.tables()) {
            String tableName = qualified(table.name(), target);
            if (tableName == null) {
                diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                        "UNSAFE_TABLE_NAME", "Unsupported table identifier: " + table.name(), "ERROR"));
                continue;
            }
            List<String> definitions = new ArrayList<>();
            boolean complete = true;
            for (SqlDdlParser.Column column : table.columns()) {
                String name = quote(column.name(), target);
                String type = mapType(column, DatabaseDialect.from(parsed.vendor()), target);
                if (name == null || type == null) {
                    complete = false;
                    diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                            "UNSUPPORTED_COLUMN_TYPE", "No safe " + target + " mapping for "
                                    + table.name() + "." + column.name() + " (" + column.dataType() + ").",
                            "REVIEW_REQUIRED"));
                    continue;
                }
                definitions.add("  " + name + " " + type + (column.nullable() ? "" : " NOT NULL"));
                if (column.defaultExpression() != null && !column.defaultExpression().isBlank()) {
                    diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                            "DEFAULT_REQUIRES_REVIEW", "Default omitted on " + table.name() + "."
                                    + column.name() + ": " + column.defaultExpression(), "REVIEW_REQUIRED"));
                }
            }
            for (SqlDdlParser.Constraint constraint : table.constraints()) {
                String mapped = mapConstraint(constraint, target, schema, diagnostics);
                if (mapped != null) definitions.add("  " + mapped);
            }
            if (!complete || definitions.isEmpty()) {
                diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                        "TABLE_NOT_EMITTED", "Table " + table.name()
                                + " was omitted because its definition is incomplete.", "REVIEW_REQUIRED"));
                continue;
            }
            statements.add("CREATE TABLE " + quote(schema, target) + "." + tableName
                    + " (\n" + String.join(",\n", definitions) + "\n);");
        }

        for (SqlDdlParser.Index index : parsed.indexes()) {
            String name = quote(index.name(), target);
            String table = qualified(index.tableName(), target);
            List<String> columns = quoteAll(index.columns(), target);
            if (name == null || table == null || columns.isEmpty()) {
                diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                        "INDEX_REQUIRES_REVIEW", "Index " + index.name()
                                + " contains unsupported identifiers and was omitted.", "REVIEW_REQUIRED"));
                continue;
            }
            statements.add("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + name
                    + " ON " + quote(schema, target) + "." + table
                    + " (" + String.join(", ", columns) + ");");
        }

        diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                "MANUAL_REVIEW_REQUIRED",
                "Review identity generation, sequences, defaults, collations, triggers, partitioning, "
                        + "vendor extensions, constraint semantics and data conversion before execution.",
                "REVIEW_REQUIRED"));
        return result(parsed, target, schema, statements, diagnostics);
    }

    private static DatabaseMigrationPlanner.MigrationPlan result(
            SqlDdlParser.ParseResult parsed, DatabaseDialect target, String schema,
            List<String> statements, List<DatabaseMigrationPlanner.Diagnostic> diagnostics) {
        return new DatabaseMigrationPlanner.MigrationPlan(
                parsed.vendor(), target.name(), schema, statements, diagnostics, true);
    }

    private static String defaultSchema(DatabaseDialect dialect) {
        return switch (dialect) {
            case ORACLE -> "APP";
            case SQLSERVER -> "dbo";
            case DB2 -> "APP";
            default -> "public";
        };
    }

    private static String mapType(SqlDdlParser.Column column, DatabaseDialect source, DatabaseDialect target) {
        String raw = column.dataType() == null ? ""
                : column.dataType().trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        int length = column.length() == null ? 0 : column.length();
        int scale = column.scale() == null ? 0 : column.scale();

        if (Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC").contains(raw)) {
            if (length <= 0) return target == DatabaseDialect.ORACLE ? "NUMBER" : "NUMERIC";
            if (scale == 0 && target == DatabaseDialect.POSTGRESQL) {
                if (length <= 4) return "SMALLINT";
                if (length <= 9) return "INTEGER";
                if (length <= 18) return "BIGINT";
            }
            if (scale == 0 && (target == DatabaseDialect.MYSQL || target == DatabaseDialect.MARIADB)) {
                if (length <= 9) return "INT";
                if (length <= 18) return "BIGINT";
            }
            String type = target == DatabaseDialect.ORACLE ? "NUMBER"
                    : (target == DatabaseDialect.MYSQL || target == DatabaseDialect.MARIADB
                    || target == DatabaseDialect.SQLSERVER || target == DatabaseDialect.DB2 ? "DECIMAL" : "NUMERIC");
            return type + "(" + length + "," + scale + ")";
        }
        if (Set.of("INT", "INTEGER", "SMALLINT", "BIGINT", "TINYINT").contains(raw)) {
            return switch (target) {
                case ORACLE -> raw.equals("BIGINT") ? "NUMBER(19)" : raw.equals("SMALLINT") ? "NUMBER(5)" : "NUMBER(10)";
                case POSTGRESQL -> raw.equals("TINYINT") ? "SMALLINT" : raw.equals("INT") ? "INTEGER" : raw;
                case MYSQL, MARIADB -> raw.equals("INTEGER") ? "INT" : raw;
                case SQLSERVER -> raw.equals("INT") || raw.equals("INTEGER") ? "INT" : raw;
                case DB2 -> raw.equals("TINYINT") ? "SMALLINT" : raw.equals("INT") ? "INTEGER" : raw;
                default -> null;
            };
        }
        if (raw.startsWith("VARCHAR") || raw.startsWith("NVARCHAR")
                || raw.startsWith("CHARACTER VARYING")) {
            if (length <= 0) return switch (target) {
                case ORACLE, DB2 -> "CLOB";
                case POSTGRESQL -> "TEXT";
                case MYSQL, MARIADB -> "LONGTEXT";
                case SQLSERVER -> "NVARCHAR(MAX)";
                default -> null;
            };
            return switch (target) {
                case ORACLE -> "VARCHAR2(" + length + ")";
                case SQLSERVER -> raw.startsWith("N") ? "NVARCHAR(" + length + ")" : "VARCHAR(" + length + ")";
                default -> "VARCHAR(" + length + ")";
            };
        }
        if (raw.equals("CHAR") || raw.equals("NCHAR")) {
            if (length <= 0) return target == DatabaseDialect.SQLSERVER && raw.equals("NCHAR") ? "NCHAR" : "CHAR";
            return target == DatabaseDialect.SQLSERVER && raw.equals("NCHAR")
                    ? "NCHAR(" + length + ")" : "CHAR(" + length + ")";
        }
        if (Set.of("CLOB", "NCLOB", "TEXT", "LONGTEXT", "NTEXT").contains(raw)) {
            return switch (target) {
                case ORACLE, DB2 -> "CLOB";
                case POSTGRESQL -> "TEXT";
                case MYSQL, MARIADB -> "LONGTEXT";
                case SQLSERVER -> "NVARCHAR(MAX)";
                default -> null;
            };
        }
        if (Set.of("BLOB", "BYTEA", "IMAGE", "VARBINARY", "BINARY", "RAW").contains(raw)) {
            return switch (target) {
                case ORACLE, DB2 -> "BLOB";
                case POSTGRESQL -> "BYTEA";
                case MYSQL, MARIADB -> "LONGBLOB";
                case SQLSERVER -> "VARBINARY(MAX)";
                default -> null;
            };
        }
        if (raw.equals("DATE")) {
            if (source != DatabaseDialect.ORACLE) return "DATE";
            return switch (target) {
                case ORACLE -> "DATE";
                case POSTGRESQL -> "TIMESTAMP(0) WITHOUT TIME ZONE";
                case MYSQL, MARIADB -> "DATETIME(0)";
                case SQLSERVER -> "DATETIME2(0)";
                case DB2 -> "TIMESTAMP(0)";
                default -> null;
            };
        }
        if (raw.startsWith("TIMESTAMP") || raw.equals("DATETIME") || raw.equals("DATETIME2")) {
            int precision = column.length() == null ? 6 : Math.min(Math.max(length, 0), 7);
            return switch (target) {
                case ORACLE -> "TIMESTAMP(" + Math.min(precision, 9) + ")";
                case POSTGRESQL -> "TIMESTAMP(" + Math.min(precision, 6) + ") WITHOUT TIME ZONE";
                case MYSQL, MARIADB -> "DATETIME(" + Math.min(precision, 6) + ")";
                case SQLSERVER -> "DATETIME2(" + precision + ")";
                case DB2 -> "TIMESTAMP(" + Math.min(precision, 12) + ")";
                default -> null;
            };
        }
        if (Set.of("FLOAT", "REAL", "DOUBLE", "DOUBLE PRECISION", "BINARY_FLOAT", "BINARY_DOUBLE").contains(raw)) {
            return switch (target) {
                case ORACLE -> raw.equals("REAL") || raw.equals("BINARY_FLOAT") ? "BINARY_FLOAT" : "BINARY_DOUBLE";
                case POSTGRESQL -> raw.equals("REAL") || raw.equals("BINARY_FLOAT") ? "REAL" : "DOUBLE PRECISION";
                case MYSQL, MARIADB -> raw.equals("REAL") || raw.equals("BINARY_FLOAT") ? "FLOAT" : "DOUBLE";
                case SQLSERVER -> raw.equals("REAL") || raw.equals("BINARY_FLOAT") ? "REAL" : "FLOAT";
                case DB2 -> raw.equals("REAL") || raw.equals("BINARY_FLOAT") ? "REAL" : "DOUBLE";
                default -> null;
            };
        }
        if (Set.of("BOOLEAN", "BOOL", "BIT").contains(raw)) {
            return switch (target) {
                case ORACLE -> "NUMBER(1)";
                case POSTGRESQL, MYSQL, MARIADB -> "BOOLEAN";
                case SQLSERVER -> "BIT";
                case DB2 -> "SMALLINT";
                default -> null;
            };
        }
        if (Set.of("UUID", "UNIQUEIDENTIFIER").contains(raw)) {
            return switch (target) {
                case ORACLE -> "RAW(16)";
                case POSTGRESQL -> "UUID";
                case MYSQL, MARIADB -> "BINARY(16)";
                case SQLSERVER -> "UNIQUEIDENTIFIER";
                case DB2 -> "CHAR(16) FOR BIT DATA";
                default -> null;
            };
        }
        return null;
    }

    private static String mapConstraint(SqlDdlParser.Constraint constraint, DatabaseDialect target,
                                        List<DatabaseMigrationPlanner.Diagnostic> diagnostics) {
        String name = quote(constraint.name(), target);
        List<String> columns = quoteAll(constraint.columns(), target);
        if (name == null || columns.isEmpty()) {
            diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                    "CONSTRAINT_REQUIRES_REVIEW", "Skipped unsupported constraint " + constraint.name(), "REVIEW_REQUIRED"));
            return null;
        }
        String prefix = "CONSTRAINT " + name + " ";
        return switch (constraint.type()) {
            case "PRIMARY_KEY" -> prefix + "PRIMARY KEY (" + String.join(", ", columns) + ")";
            case "UNIQUE" -> prefix + "UNIQUE (" + String.join(", ", columns) + ")";
            case "FOREIGN_KEY" -> {
                String referencedTable = qualified(constraint.referencedTable(), target);\n                String referenced = referencedTable == null ? null : quote(schema, target) + "." + referencedTable;
                List<String> referencedColumns = quoteAll(constraint.referencedColumns(), target);
                if (referenced == null || referencedColumns.isEmpty()) {
                    diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                            "FOREIGN_KEY_REQUIRES_REVIEW", "Skipped foreign key " + constraint.name(), "REVIEW_REQUIRED"));
                    yield null;
                }
                yield prefix + "FOREIGN KEY (" + String.join(", ", columns) + ") REFERENCES "
                        + referenced + " (" + String.join(", ", referencedColumns) + ")";
            }
            default -> {
                diagnostics.add(new DatabaseMigrationPlanner.Diagnostic(
                        "UNSUPPORTED_CONSTRAINT", "Constraint " + constraint.name() + " was omitted.", "REVIEW_REQUIRED"));
                yield null;
            }
        };
    }

    private static List<String> quoteAll(List<String> values, DatabaseDialect dialect) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String quoted = quote(value, dialect);
            if (quoted == null) return List.of();
            result.add(quoted);
        }
        return result;
    }

    private static String qualified(String value, DatabaseDialect dialect) {
        if (value == null || value.isBlank()) return null;
        List<String> parts = new ArrayList<>();
        for (String part : value.split("\\.", -1)) {
            String quoted = quote(part, dialect);
            if (quoted == null) return null;
            parts.add(quoted);
        }
        return String.join(".", parts);
    }

    private static String quote(String value, DatabaseDialect dialect) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) return null;
        return switch (dialect) {
            case MYSQL, MARIADB -> "`" + value + "`";
            case SQLSERVER -> "[" + value + "]";
            default -> "\"" + value + "\"";
        };
    }
}

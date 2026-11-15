package io.aegis.transformation;

import io.aegis.parser.sql.SqlDdlParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conservative Oracle-to-PostgreSQL DDL planner for the parser's supported CREATE TABLE subset.
 * It emits reviewable SQL only; it never connects to or mutates a database.
 */
public final class OraclePostgresDdlPlanner {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    public MigrationPlan plan(SqlDdlParser.ParseResult parsed, String targetSchema) {
        if (parsed == null) throw new IllegalArgumentException("Parsed DDL is required");
        String schema = targetSchema == null || targetSchema.isBlank() ? "public" : targetSchema.trim();
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<String> statements = new ArrayList<>();
        if (!"ORACLE".equalsIgnoreCase(parsed.vendor())) {
            diagnostics.add(new Diagnostic("SOURCE_DIALECT_NOT_ORACLE",
                    "This planner accepts Oracle input only; received " + parsed.vendor(), "REVIEW_REQUIRED"));
            return new MigrationPlan("POSTGRESQL", schema, statements, diagnostics, true);
        }
        if (!SAFE_IDENTIFIER.matcher(schema).matches()) {
            diagnostics.add(new Diagnostic("UNSAFE_TARGET_SCHEMA",
                    "Target schema must be a simple SQL identifier.", "ERROR"));
            return new MigrationPlan("POSTGRESQL", schema, statements, diagnostics, true);
        }
        for (var parserDiagnostic : parsed.diagnostics()) {
            diagnostics.add(new Diagnostic("SOURCE_" + parserDiagnostic.code(),
                    parserDiagnostic.message(), "REVIEW_REQUIRED"));
        }
        if (parsed.tables().isEmpty()) {
            diagnostics.add(new Diagnostic("NO_SUPPORTED_TABLES",
                    "No supported CREATE TABLE statements were found.", "ERROR"));
        }
        for (SqlDdlParser.Table table : parsed.tables()) {
            String tableName = quoteQualified(table.name());
            if (tableName == null) {
                diagnostics.add(new Diagnostic("UNSAFE_TABLE_NAME",
                        "Skipped table with unsupported identifier: " + table.name(), "ERROR"));
                continue;
            }
            List<String> definitions = new ArrayList<>();
            boolean usable = true;
            for (SqlDdlParser.Column column : table.columns()) {
                String name = quoteIdentifier(column.name());
                String type = mapType(column, diagnostics, table.name());
                if (name == null || type == null) {
                    usable = false;
                    diagnostics.add(new Diagnostic("UNSUPPORTED_COLUMN",
                            "Skipped table " + table.name() + " because column " + column.name()
                                    + " could not be mapped safely.", "REVIEW_REQUIRED"));
                    continue;
                }
                definitions.add("  " + name + " " + type + (column.nullable() ? "" : " NOT NULL"));
                if (column.defaultExpression() != null && !column.defaultExpression().isBlank()) {
                    diagnostics.add(new Diagnostic("DEFAULT_REQUIRES_REVIEW",
                            "Oracle default expression on " + table.name() + "." + column.name()
                                    + " was omitted: " + column.defaultExpression(), "REVIEW_REQUIRED"));
                }
            }
            for (SqlDdlParser.Constraint constraint : table.constraints()) {
                String clause = mapConstraint(constraint, diagnostics, table.name());
                if (clause != null) definitions.add("  " + clause);
            }
            if (definitions.isEmpty() || !usable) {
                diagnostics.add(new Diagnostic("TABLE_NOT_EMITTED",
                        "Table " + table.name() + " was not emitted completely; resolve column diagnostics first.",
                        "REVIEW_REQUIRED"));
                continue;
            }
            statements.add("CREATE TABLE " + quoteIdentifier(schema) + "." + tableName + " (\n"
                    + String.join(",\n", definitions) + "\n);");
        }
        diagnostics.add(new Diagnostic("MANUAL_REVIEW_REQUIRED",
                "Review identifiers, defaults, sequences/identity, indexes, triggers, PL/SQL, constraints and data semantics before execution.",
                "REVIEW_REQUIRED"));
        return new MigrationPlan("POSTGRESQL", schema, statements, diagnostics, true);
    }

    private static String mapType(SqlDdlParser.Column column, List<Diagnostic> diagnostics, String table) {
        String raw = column.dataType().trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        Integer length = column.length();
        Integer scale = column.scale();
        if (raw.equals("NUMBER") || raw.equals("NUMERIC") || raw.equals("DECIMAL")) {
            if (length == null) return "NUMERIC";
            int s = scale == null ? 0 : scale;
            if (s == 0 && length <= 4) return "SMALLINT";
            if (s == 0 && length <= 9) return "INTEGER";
            if (s == 0 && length <= 18) return "BIGINT";
            return "NUMERIC(" + length + "," + s + ")";
        }
        if (raw.equals("VARCHAR2") || raw.equals("VARCHAR") || raw.equals("NVARCHAR2")) {
            if (length == null || length < 1) {
                diagnostics.add(new Diagnostic("VARCHAR_LENGTH_MISSING",
                        "No length was parsed for " + table + "." + column.name() + "; emitted TEXT.", "REVIEW_REQUIRED"));
                return "TEXT";
            }
            return "VARCHAR(" + length + ")";
        }
        if (raw.equals("CHAR") || raw.equals("NCHAR"))
            return length == null || length < 1 ? "CHAR" : "CHAR(" + length + ")";
        if (raw.equals("CLOB") || raw.equals("NCLOB")) return "TEXT";
        if (raw.equals("BLOB") || raw.equals("RAW")) return "BYTEA";
        if (raw.equals("DATE")) return "TIMESTAMP(0) WITHOUT TIME ZONE";
        if (raw.startsWith("TIMESTAMP")) {
            if (raw.contains("WITH TIME ZONE")) return "TIMESTAMP" + precision(length) + " WITH TIME ZONE";
            if (raw.contains("LOCAL TIME ZONE")) {
                diagnostics.add(new Diagnostic("LOCAL_TIME_ZONE_REVIEW",
                        "Oracle TIMESTAMP WITH LOCAL TIME ZONE requires an explicit timezone policy for "
                                + table + "." + column.name(), "REVIEW_REQUIRED"));
                return "TIMESTAMP" + precision(length) + " WITH TIME ZONE";
            }
            return "TIMESTAMP" + precision(length) + " WITHOUT TIME ZONE";
        }
        if (raw.equals("FLOAT") || raw.equals("BINARY_DOUBLE")) return "DOUBLE PRECISION";
        if (raw.equals("BINARY_FLOAT")) return "REAL";
        if (raw.equals("INTEGER") || raw.equals("INT")) return "INTEGER";
        if (raw.equals("SMALLINT")) return "SMALLINT";
        if (raw.equals("BIGINT")) return "BIGINT";
        diagnostics.add(new Diagnostic("UNSUPPORTED_ORACLE_TYPE",
                "No automatic PostgreSQL mapping for " + raw + " at " + table + "." + column.name(),
                "REVIEW_REQUIRED"));
        return null;
    }

    private static String precision(Integer value) {
        return value == null ? "" : "(" + value + ")";
    }

    private static String mapConstraint(SqlDdlParser.Constraint c, List<Diagnostic> diagnostics, String table) {
        String name = quoteIdentifier(c.name());
        List<String> columns = quoteIdentifiers(c.columns());
        if (name == null || columns.isEmpty()) {
            diagnostics.add(new Diagnostic("CONSTRAINT_REQUIRES_REVIEW",
                    "Skipped constraint with unsupported name/columns on " + table + ": " + c.name(),
                    "REVIEW_REQUIRED"));
            return null;
        }
        String prefix = "CONSTRAINT " + name + " ";
        return switch (c.type()) {
            case "PRIMARY_KEY" -> prefix + "PRIMARY KEY (" + String.join(", ", columns) + ")";
            case "UNIQUE" -> prefix + "UNIQUE (" + String.join(", ", columns) + ")";
            case "FOREIGN_KEY" -> {
                String referenced = quoteQualified(c.referencedTable());
                List<String> referencedColumns = quoteIdentifiers(c.referencedColumns());
                if (referenced == null || referencedColumns.isEmpty()) {
                    diagnostics.add(new Diagnostic("FOREIGN_KEY_REQUIRES_REVIEW",
                            "Skipped foreign key with unsupported target on " + table, "REVIEW_REQUIRED"));
                    yield null;
                }
                yield prefix + "FOREIGN KEY (" + String.join(", ", columns) + ") REFERENCES "
                        + referenced + " (" + String.join(", ", referencedColumns) + ")";
            }
            default -> {
                diagnostics.add(new Diagnostic("UNSUPPORTED_CONSTRAINT",
                        "Constraint " + c.name() + " of type " + c.type() + " on " + table + " was omitted.",
                        "REVIEW_REQUIRED"));
                yield null;
            }
        };
    }

    private static List<String> quoteIdentifiers(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String quoted = quoteIdentifier(value);
            if (quoted == null) return List.of();
            result.add(quoted);
        }
        return result;
    }

    private static String quoteQualified(String value) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.split("\\.", -1);
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            String item = quoteIdentifier(part);
            if (item == null) return null;
            quoted.add(item);
        }
        return String.join(".", quoted);
    }

    private static String quoteIdentifier(String value) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) return null;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public record Diagnostic(String code, String message, String status) {}
    public record MigrationPlan(String targetDialect, String targetSchema, List<String> statements,
                                List<Diagnostic> diagnostics, boolean humanReviewRequired) {
        public MigrationPlan {
            statements = List.copyOf(statements);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}

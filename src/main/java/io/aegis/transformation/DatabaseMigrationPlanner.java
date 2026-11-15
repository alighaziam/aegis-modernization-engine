package io.aegis.transformation;

import io.aegis.parser.sql.DatabaseDialect;
import io.aegis.parser.sql.SqlDdlParser;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Routes supported DDL source/target pairs through conservative, reviewable adapters.
 * This planner emits SQL only for the common parsed subset; it never executes migrations.
 */
public final class DatabaseMigrationPlanner {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private final OraclePostgresDdlPlanner oraclePostgres = new OraclePostgresDdlPlanner();
    private final CommonDdlMigrationAdapter commonAdapter = new CommonDdlMigrationAdapter();

    public MigrationPlan plan(SqlDdlParser.ParseResult parsed, String targetDialect, String targetSchema) {
        if (parsed == null) throw new IllegalArgumentException("Parsed source DDL is required");
        DatabaseDialect source = DatabaseDialect.from(parsed.vendor());
        DatabaseDialect target = DatabaseDialect.from(targetDialect);
        String schema = targetSchema == null || targetSchema.isBlank() ? defaultSchema(target) : targetSchema.trim();

        if (source == DatabaseDialect.UNKNOWN || target == DatabaseDialect.UNKNOWN) {
            return new MigrationPlan(source.name(), target.name(), schema, List.of(),
                    List.of(new Diagnostic("UNKNOWN_DIALECT",
                            "Source or target database dialect is not registered; no SQL was generated.", "ERROR")), true);
        }
        if (!SAFE_IDENTIFIER.matcher(schema).matches()) {
            return new MigrationPlan(source.name(), target.name(), schema, List.of(),
                    List.of(new Diagnostic("UNSAFE_TARGET_SCHEMA",
                            "Target schema must be a simple SQL identifier.", "ERROR")), true);
        }
        if (source == DatabaseDialect.ORACLE && target == DatabaseDialect.POSTGRESQL) {
            OraclePostgresDdlPlanner.MigrationPlan plan = oraclePostgres.plan(parsed, schema);
            List<Diagnostic> diagnostics = plan.diagnostics().stream()
                    .map(d -> new Diagnostic(d.code(), d.message(), d.status())).toList();
            return new MigrationPlan(source.name(), target.name(), schema, plan.statements(), diagnostics, true);
        }
        return commonAdapter.plan(parsed, target, schema);
    }

    private static String defaultSchema(DatabaseDialect target) {
        return switch (target) {
            case ORACLE, DB2 -> "APP";
            case SQLSERVER -> "dbo";
            default -> "public";
        };
    }

    public record Diagnostic(String code, String message, String status) {}
    public record MigrationPlan(String sourceDialect, String targetDialect, String targetSchema,
                                List<String> statements, List<Diagnostic> diagnostics,
                                boolean humanReviewRequired) {
        public MigrationPlan {
            statements = List.copyOf(statements);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}

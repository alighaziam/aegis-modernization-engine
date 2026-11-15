package io.aegis.transformation;

import io.aegis.parser.sql.DatabaseDialect;
import io.aegis.parser.sql.SqlDdlParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Routes DDL migration requests through explicitly tested source/target adapters.
 * Unsupported pairs are reported as such; no fallback or guessed SQL is generated.
 */
public final class DatabaseMigrationPlanner {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private final OraclePostgresDdlPlanner oraclePostgres = new OraclePostgresDdlPlanner();

    public MigrationPlan plan(SqlDdlParser.ParseResult parsed, String targetDialect, String targetSchema) {
        if (parsed == null) throw new IllegalArgumentException("Parsed source DDL is required");
        DatabaseDialect source = DatabaseDialect.from(parsed.vendor());
        DatabaseDialect target = DatabaseDialect.from(targetDialect);
        String schema = targetSchema == null || targetSchema.isBlank() ? "public" : targetSchema.trim();
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
        return new MigrationPlan(source.name(), target.name(), schema, List.of(),
                List.of(new Diagnostic("MIGRATION_ADAPTER_NOT_IMPLEMENTED",
                        "No tested DDL transformation adapter exists for " + source
                                + " -> " + target + ". The source can be analyzed, but conversion SQL was not generated.",
                        "REVIEW_REQUIRED")), true);
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

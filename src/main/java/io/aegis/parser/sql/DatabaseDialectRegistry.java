package io.aegis.parser.sql;

import java.util.List;
import java.util.Optional;

/**
 * Explicit capability registry. Recognition is not a claim of full grammar or migration support.
 * All listed dialects share only the conservative common CREATE TABLE/INDEX subset parser.
 */
public final class DatabaseDialectRegistry {
    private static final List<Capability> CAPABILITIES = List.of(
            new Capability(DatabaseDialect.ORACLE, true, true, true, "Common DDL subset; Oracle-to-PostgreSQL has a specialized planner; other pairs use conservative common mappings"),
            new Capability(DatabaseDialect.POSTGRESQL, true, false, true, "Common DDL subset; conservative cross-dialect mappings only"),
            new Capability(DatabaseDialect.MYSQL, true, false, true, "Common DDL subset; vendor-specific syntax is diagnostic"),
            new Capability(DatabaseDialect.MARIADB, true, false, true, "Common DDL subset; vendor-specific syntax is diagnostic"),
            new Capability(DatabaseDialect.SQLSERVER, true, false, true, "Common DDL subset; T-SQL-specific syntax is diagnostic"),
            new Capability(DatabaseDialect.DB2, true, false, true, "Common DDL subset; DB2-specific syntax is diagnostic")
    );

    public List<Capability> all() { return CAPABILITIES; }

    public Optional<Capability> find(String dialect) {
        DatabaseDialect parsed = DatabaseDialect.from(dialect);
        return CAPABILITIES.stream().filter(c -> c.dialect() == parsed).findFirst();
    }

    public record Capability(DatabaseDialect dialect, boolean commonDdlParser,
                             boolean oracleToPostgresSource, boolean generalMigrationPlanner,
                             String limitations) {}
}

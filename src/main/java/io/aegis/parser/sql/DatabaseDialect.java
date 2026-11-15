package io.aegis.parser.sql;

import java.util.Locale;

/** Canonical database dialect identifiers accepted by the analysis pipeline. */
public enum DatabaseDialect {
    ORACLE, POSTGRESQL, MYSQL, MARIADB, SQLSERVER, DB2, UNKNOWN;

    public static DatabaseDialect from(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "oracle" -> ORACLE;
            case "postgres", "postgresql" -> POSTGRESQL;
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            case "sqlserver", "sql-server", "mssql" -> SQLSERVER;
            case "db2" -> DB2;
            default -> UNKNOWN;
        };
    }
}

package io.aegis.parser.sql;

import java.util.Locale;

/** Canonical database dialect identifiers accepted by the analysis pipeline. */
public enum DatabaseDialect {
    ORACLE, POSTGRESQL, MYSQL, MARIADB, SQLSERVER, DB2, UNKNOWN;

    public static DatabaseDialect from(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-').replaceAll("[\\s-]+", "");
        if (normalized.startsWith("oracle")) return ORACLE;
        if (normalized.equals("postgres") || normalized.startsWith("postgresql")) return POSTGRESQL;
        if (normalized.startsWith("mariadb")) return MARIADB;
        if (normalized.startsWith("mysql")) return MYSQL;
        if (normalized.contains("sqlserver") || normalized.equals("mssql")) return SQLSERVER;
        if (normalized.startsWith("db2") || normalized.startsWith("ibmdb2")) return DB2;
        return UNKNOWN;
    }
}

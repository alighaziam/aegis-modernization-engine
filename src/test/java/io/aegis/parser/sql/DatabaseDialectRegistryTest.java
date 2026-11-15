package io.aegis.parser.sql;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseDialectRegistryTest {
    @Test
    void recognizesCommonDatabaseVendorAliases() {
        assertEquals(DatabaseDialect.ORACLE, DatabaseDialect.from("oracle"));
        assertEquals(DatabaseDialect.POSTGRESQL, DatabaseDialect.from("postgres"));
        assertEquals(DatabaseDialect.MYSQL, DatabaseDialect.from("mysql"));
        assertEquals(DatabaseDialect.MARIADB, DatabaseDialect.from("mariadb"));
        assertEquals(DatabaseDialect.SQLSERVER, DatabaseDialect.from("mssql"));
        assertEquals(DatabaseDialect.DB2, DatabaseDialect.from("db2"));
        assertEquals(DatabaseDialect.UNKNOWN, DatabaseDialect.from("unlisted-vendor"));
    }

    @Test
    void reportsCapabilitiesWithoutClaimingFullMigrationSupport() {
        DatabaseDialectRegistry registry = new DatabaseDialectRegistry();
        assertTrue(registry.find("mysql").orElseThrow().commonDdlParser());
        assertFalse(registry.find("mysql").orElseThrow().generalMigrationPlanner());
        assertTrue(registry.find("oracle").orElseThrow().oracleToPostgresSource());
        assertTrue(registry.find("sqlserver").isPresent());
        assertTrue(registry.find("db2").isPresent());
    }
}

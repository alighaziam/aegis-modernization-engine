package io.aegis.parser.sql;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseDialectRegistryTest {
    @Test
    void recognizesCommonDatabaseVendorAliases() {
        assertEquals(DatabaseDialect.ORACLE, DatabaseDialect.from("oracle"));
        assertEquals(DatabaseDialect.ORACLE, DatabaseDialect.from("Oracle Database 19c"));
        assertEquals(DatabaseDialect.POSTGRESQL, DatabaseDialect.from("postgres"));
        assertEquals(DatabaseDialect.MYSQL, DatabaseDialect.from("mysql"));
        assertEquals(DatabaseDialect.MARIADB, DatabaseDialect.from("mariadb"));
        assertEquals(DatabaseDialect.SQLSERVER, DatabaseDialect.from("mssql"));
        assertEquals(DatabaseDialect.SQLSERVER, DatabaseDialect.from("Microsoft SQL Server"));
        assertEquals(DatabaseDialect.DB2, DatabaseDialect.from("db2"));
        assertEquals(DatabaseDialect.DB2, DatabaseDialect.from("IBM DB2"));
        assertEquals(DatabaseDialect.UNKNOWN, DatabaseDialect.from("unlisted-vendor"));
    }

    @Test
    void reportsConservativeCommonSubsetMigrationCapabilities() {
        DatabaseDialectRegistry registry = new DatabaseDialectRegistry();
        assertTrue(registry.find("mysql").orElseThrow().commonDdlParser());
        assertTrue(registry.find("mysql").orElseThrow().generalMigrationPlanner());
        assertTrue(registry.find("mysql").orElseThrow().limitations().contains("Common DDL subset"));
        assertTrue(registry.find("oracle").orElseThrow().oracleToPostgresSource());
        assertTrue(registry.find("sqlserver").isPresent());
        assertTrue(registry.find("db2").isPresent());
    }
}

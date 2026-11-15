package io.aegis.transformation;

import io.aegis.parser.sql.SqlDdlParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationPlannerTest {
    private final SqlDdlParser parser = new SqlDdlParser();
    private final DatabaseMigrationPlanner planner = new DatabaseMigrationPlanner();

    @Test
    void delegatesOnlyTheTestedOracleToPostgresAdapter() {
        var parsed = parser.parse("CREATE TABLE CUSTOMER (ID NUMBER(9) NOT NULL);", "customer.sql", "oracle");
        var plan = planner.plan(parsed, "postgresql", "public");
        assertEquals("ORACLE", plan.sourceDialect());
        assertEquals("POSTGRESQL", plan.targetDialect());
        assertFalse(plan.statements().isEmpty());
        assertTrue(plan.humanReviewRequired());
    }

    @Test
    void reportsKnownButUnsupportedMigrationPairsWithoutInventingSql() {
        var parsed = parser.parse("CREATE TABLE customer (id BIGINT);", "customer.sql", "mysql");
        var plan = planner.plan(parsed, "sqlserver", "dbo");
        assertTrue(plan.statements().isEmpty());
        assertEquals("MIGRATION_ADAPTER_NOT_IMPLEMENTED", plan.diagnostics().getFirst().code());
        assertTrue(plan.humanReviewRequired());
    }

    @Test
    void rejectsUnknownDialectsAndUnsafeSchemas() {
        var parsed = parser.parse("CREATE TABLE t (id BIGINT);", "t.sql", "mystery-db");
        assertEquals("UNKNOWN_DIALECT", planner.plan(parsed, "postgresql", "public").diagnostics().getFirst().code());
        var oracle = parser.parse("CREATE TABLE t (id NUMBER(9));", "t.sql", "oracle");
        assertEquals("UNSAFE_TARGET_SCHEMA",
                planner.plan(oracle, "postgresql", "public; DROP TABLE users").diagnostics().getFirst().code());
    }
}

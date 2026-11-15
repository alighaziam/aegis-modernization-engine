package io.aegis.transformation;

import io.aegis.parser.sql.SqlDdlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationPlannerTest {
    private final SqlDdlParser parser = new SqlDdlParser();
    private final DatabaseMigrationPlanner planner = new DatabaseMigrationPlanner();

    @Test
    void delegatesOracleToPostgresToItsSpecializedAdapter() {
        var parsed = parser.parse("CREATE TABLE CUSTOMER (ID NUMBER(9) NOT NULL);", "customer.sql", "oracle");
        var plan = planner.plan(parsed, "postgresql", "public");
        assertEquals("ORACLE", plan.sourceDialect());
        assertEquals("POSTGRESQL", plan.targetDialect());
        assertFalse(plan.statements().isEmpty());
        assertTrue(plan.humanReviewRequired());
    }

    @Test
    void generatesConservativePlansForEveryRegisteredDialectPair() {
        String ddl = """
                CREATE TABLE CUSTOMER (
                  ID BIGINT NOT NULL,
                  NAME VARCHAR(80),
                  CREATED_AT TIMESTAMP,
                  CONSTRAINT PK_CUSTOMER PRIMARY KEY (ID)
                );
                CREATE INDEX IX_CUSTOMER_NAME ON CUSTOMER (NAME);
                """;
        List<String> dialects = List.of("oracle", "postgresql", "mysql", "mariadb", "sqlserver", "db2");
        for (String source : dialects) {
            var parsed = parser.parse(ddl, "customer.sql", source);
            for (String target : dialects) {
                var plan = planner.plan(parsed, target, "app");
                assertFalse(plan.statements().isEmpty(), source + " -> " + target + " should emit supported DDL");
                assertTrue(plan.humanReviewRequired(), source + " -> " + target + " must require human review");
            }
        }
    }

    @Test
    void reportsUnsupportedTypesWithoutEmittingAnIncompleteTable() {
        var parsed = parser.parse("CREATE TABLE T (ID BIGINT, GEO SDO_GEOMETRY);", "t.sql", "oracle");
        var plan = planner.plan(parsed, "mysql", "app");
        assertTrue(plan.statements().isEmpty());
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("UNSUPPORTED_COLUMN_TYPE")));
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("TABLE_NOT_EMITTED")));
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

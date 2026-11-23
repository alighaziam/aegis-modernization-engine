package io.aegis.transformation;

import io.aegis.parser.sql.SqlDdlParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OraclePostgresDdlPlannerTest {
    private final SqlDdlParser parser = new SqlDdlParser();
    private final OraclePostgresDdlPlanner planner = new OraclePostgresDdlPlanner();

    @Test void mapsCommonOracleColumnTypesAndDeclaredConstraints() {
        String ddl = """
                CREATE TABLE CUSTOMER (
                  ID NUMBER(12,0) NOT NULL,
                  NAME VARCHAR2(120) NOT NULL,
                  CREATED_AT DATE,
                  NOTES CLOB,
                  PAYLOAD BLOB,
                  CONSTRAINT PK_CUSTOMER PRIMARY KEY (ID),
                  CONSTRAINT UQ_CUSTOMER_NAME UNIQUE (NAME)
                );
                """;
        var parsed = parser.parse(ddl, "customer.sql", "oracle");
        var plan = planner.plan(parsed, "app");
        assertEquals(1, plan.statements().size());
        String sql = plan.statements().getFirst();
        assertTrue(sql.contains("\"ID\" BIGINT NOT NULL"));
        assertTrue(sql.contains("\"NAME\" VARCHAR(120) NOT NULL"));
        assertTrue(sql.contains("\"CREATED_AT\" TIMESTAMP(0) WITHOUT TIME ZONE"));
        assertTrue(sql.contains("\"NOTES\" TEXT"));
        assertTrue(sql.contains("\"PAYLOAD\" BYTEA"));
        assertTrue(sql.contains("PRIMARY KEY (\"ID\")"));
        assertTrue(sql.contains("UNIQUE (\"NAME\")"));
        assertTrue(plan.humanReviewRequired());
    }

    @Test void omitsOracleDefaultsAndSurfacesReviewInsteadOfCopyingThem() {
        var parsed = parser.parse("CREATE TABLE T (ID NUMBER(9) DEFAULT T_SEQ.NEXTVAL);", "t.sql", "oracle");
        var plan = planner.plan(parsed, "public");
        assertEquals(1, plan.statements().size());
        assertFalse(plan.statements().getFirst().contains("DEFAULT"));
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("DEFAULT_REQUIRES_REVIEW")));
    }

    @Test void rejectsNonOracleSourceAndUnsafeSchema() {
        var parsed = parser.parse("CREATE TABLE t (id BIGINT);", "t.sql", "postgresql");
        assertTrue(planner.plan(parsed, "public").statements().isEmpty());
        var oracle = parser.parse("CREATE TABLE t (id NUMBER(9));", "t.sql", "oracle");
        assertTrue(planner.plan(oracle, "bad; DROP SCHEMA public").diagnostics()
                .stream().anyMatch(d -> d.code().equals("UNSAFE_TARGET_SCHEMA")));
    }

    @Test void flagsUnsupportedColumnTypesWithoutInventingMappings() {
        var parsed = parser.parse("CREATE TABLE T (ID NUMBER(9), GEO SDO_GEOMETRY);", "t.sql", "oracle");
        var plan = planner.plan(parsed, "public");
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("UNSUPPORTED_ORACLE_TYPE")));
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.code().equals("TABLE_NOT_EMITTED")));
    }

    @Test void usesFinalAlteredColumnMetadataInOraclePostgresMigrationPlan() {
        String ddl = """
                CREATE TABLE CUSTOMER (
                  ID NUMBER(9),
                  AMOUNT NUMBER(8,2)
                );
                ALTER TABLE CUSTOMER MODIFY (AMOUNT NUMBER(12,2));
                ALTER TABLE CUSTOMER MODIFY (ID NUMBER(9) NOT NULL);
                """;
        var parsed = parser.parse(ddl, "V7__customer_amount.sql", "oracle");
        var plan = planner.plan(parsed, "public");

        assertEquals(1, plan.statements().size());
        String sql = plan.statements().getFirst();
        assertTrue(sql.contains("\"AMOUNT\" NUMERIC(12,2)"));
        assertTrue(sql.contains("\"ID\" INTEGER NOT NULL"));
        assertTrue(parsed.diagnostics().isEmpty(), () -> parsed.diagnostics().toString());
    }

}

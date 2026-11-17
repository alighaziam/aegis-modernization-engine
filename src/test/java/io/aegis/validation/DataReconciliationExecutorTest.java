package io.aegis.validation;

import io.aegis.planning.DataReconciliationPlanner;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataReconciliationExecutorTest {
    @Test
    void executesGeneratedScalarSelectChecksAndComparesSourceTargetResults() throws Exception {
        try (var source = database("aegis_exec_source", 2);
             var target = database("aegis_exec_target", 2)) {
            var report = new DataReconciliationExecutor().execute(plan(List.of(
                    check("rows", "ROW_COUNT", "SELECT COUNT(*) FROM CUSTOMERS", "SELECT COUNT(*) FROM CUSTOMERS"),
                    check("duplicates", "DUPLICATE_PRIMARY_KEYS",
                            "SELECT COUNT(*) FROM (SELECT ID FROM CUSTOMERS GROUP BY ID HAVING COUNT(*) > 1) d",
                            "SELECT COUNT(*) FROM (SELECT ID FROM CUSTOMERS GROUP BY ID HAVING COUNT(*) > 1) d"),
                    check("nulls", "NULL_PRIMARY_KEYS",
                            "SELECT COUNT(*) FROM CUSTOMERS WHERE ID IS NULL",
                            "SELECT COUNT(*) FROM CUSTOMERS WHERE ID IS NULL"))), source, target, false);

            assertTrue(report.executed());
            assertTrue(report.passed());
            assertEquals(3, report.checksExecuted());
            assertTrue(report.results().stream().allMatch(DataReconciliationExecutor.CheckResult::passed));
            assertFalse(source.isClosed());
            assertFalse(target.isClosed());
        }
    }

    @Test
    void reportsRowCountMismatchAndDoesNotMarkValidationAsPassed() throws Exception {
        try (var source = database("aegis_exec_mismatch_source", 2);
             var target = database("aegis_exec_mismatch_target", 1)) {
            var report = new DataReconciliationExecutor().execute(plan(List.of(
                    check("rows", "ROW_COUNT", "SELECT COUNT(*) FROM CUSTOMERS", "SELECT COUNT(*) FROM CUSTOMERS"))),
                    source, target, false);
            assertTrue(report.executed());
            assertFalse(report.passed());
            assertEquals(2L, report.results().getFirst().sourceValue());
            assertEquals(1L, report.results().getFirst().targetValue());
        }
    }

    @Test
    void requiresExplicitApprovalBeforeConnectingWhenPlanHasDiagnostics() {
        var plan = new DataReconciliationPlanner.Plan("source", "H2", "target", "H2", 1,
                List.of(check("rows", "ROW_COUNT", "SELECT COUNT(*) FROM CUSTOMERS",
                        "SELECT COUNT(*) FROM CUSTOMERS")),
                List.of("COLUMN_TYPE_DIFFERENCE: review"), List.of());
        var report = new DataReconciliationExecutor().execute(plan, null, null, false);
        assertFalse(report.executed());
        assertFalse(report.passed());
        assertEquals(0, report.checksExecuted());
        assertTrue(report.diagnostics().getFirst().startsWith("DIAGNOSTICS_REQUIRE_APPROVAL"));
    }

    @Test
    void rejectsNonSelectAndMultipleStatementQueriesBeforeExecution() throws Exception {
        try (var source = database("aegis_exec_invalid_source", 1);
             var target = database("aegis_exec_invalid_target", 1)) {
            var report = new DataReconciliationExecutor().execute(plan(List.of(
                    check("bad", "ROW_COUNT", "DELETE FROM CUSTOMERS", "SELECT COUNT(*) FROM CUSTOMERS"))),
                    source, target, false);
            assertFalse(report.executed());
            assertFalse(report.passed());
            assertEquals(0, report.checksExecuted());
            assertTrue(report.results().getFirst().error().contains("Only a single SELECT"));
        }
    }

    @Test
    void rejectsSelectStatementsWithMultipleCommands() throws Exception {
        try (var source = database("aegis_exec_multi_source", 1);
             var target = database("aegis_exec_multi_target", 1)) {
            var report = new DataReconciliationExecutor().execute(plan(List.of(
                    check("bad", "ROW_COUNT", "SELECT COUNT(*) FROM CUSTOMERS; DROP TABLE CUSTOMERS",
                            "SELECT COUNT(*) FROM CUSTOMERS"))), source, target, false);
            assertFalse(report.executed());
            assertFalse(report.passed());
            assertTrue(report.results().getFirst().error().contains("forbidden token"));
        }
    }

    private static java.sql.Connection database(String name, int rows) throws Exception {
        var connection = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE CUSTOMERS (ID BIGINT PRIMARY KEY)");
            for (int i = 1; i <= rows; i++) statement.execute("INSERT INTO CUSTOMERS (ID) VALUES (" + i + ")");
        }
        return connection;
    }

    private static DataReconciliationPlanner.Plan plan(List<DataReconciliationPlanner.Check> checks) {
        return new DataReconciliationPlanner.Plan("source", "H2", "target", "H2", 1,
                checks, List.of(), List.of());
    }
    private static DataReconciliationPlanner.Check check(String id, String type, String source, String target) {
        return new DataReconciliationPlanner.Check(id, type, source, target, "Expected values to reconcile.",
                "HIGH", "CUSTOMERS");
    }
}

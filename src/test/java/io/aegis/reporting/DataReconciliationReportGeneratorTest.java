package io.aegis.reporting;

import io.aegis.planning.DataReconciliationPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataReconciliationReportGeneratorTest {
    @Test
    void rendersChecksDiagnosticsAndNonExecutionSafetyNotice() {
        var plan = new DataReconciliationPlanner.Plan("source", "Oracle", "target", "PostgreSQL",
                1, List.of(new DataReconciliationPlanner.Check("customers-row-count", "ROW_COUNT",
                "SELECT COUNT(*) FROM \"HR\".\"CUSTOMERS\"",
                "SELECT COUNT(*) FROM \"public\".\"CUSTOMERS\"",
                "Row counts should match.", "HIGH", "CUSTOMERS")),
                List.of("COLUMN_TYPE_DIFFERENCE: review precision"), List.of("The planner never executes SQL."));
        String report = new DataReconciliationReportGenerator().generate(plan);
        assertTrue(report.contains("Data Reconciliation Plan"));
        assertTrue(report.contains("customers-row-count"));
        assertTrue(report.contains("COLUMN_TYPE_DIFFERENCE"));
        assertTrue(report.contains("No SQL in this plan has been executed"));
        assertTrue(report.contains("SELECT COUNT(*)"));
    }
}

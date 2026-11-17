package io.aegis.reporting;

import io.aegis.validation.DataReconciliationExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataReconciliationExecutionReportGeneratorTest {
    @Test
    void rendersExecutionValuesAndReadOnlyGuarantee() {
        var report = new DataReconciliationExecutor.ExecutionReport(true, false, 1,
                List.of(new DataReconciliationExecutor.CheckResult("orders-row-count", "ROW_COUNT",
                        10L, 9L, false, "Counts must match.", null)),
                List.of("READ_ONLY_HINT_NOT_CONFIRMED: source"));
        String markdown = new DataReconciliationExecutionReportGenerator().generate(report);
        assertTrue(markdown.contains("orders-row-count"));
        assertTrue(markdown.contains("10"));
        assertTrue(markdown.contains("9"));
        assertTrue(markdown.contains("FAIL"));
        assertTrue(markdown.contains("No DDL or data writes"));
    }
}

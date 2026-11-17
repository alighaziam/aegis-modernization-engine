package io.aegis.reporting;

import io.aegis.validation.DataReconciliationExecutor;

/** Markdown renderer for scalar source/target reconciliation execution results. */
public final class DataReconciliationExecutionReportGenerator {
    public String generate(DataReconciliationExecutor.ExecutionReport report) {
        if (report == null) throw new IllegalArgumentException("execution report is required");
        StringBuilder out = new StringBuilder("# Data Reconciliation Execution\n\n");
        out.append("**Executed:** ").append(report.executed()).append("  \n");
        out.append("**Passed:** ").append(report.passed()).append("  \n");
        out.append("**Completed checks:** ").append(report.checksExecuted()).append("\n\n");
        out.append("| Check | Type | Source value | Target value | Result | Error |\n");
        out.append("|---|---|---:|---:|---|---|\n");
        for (var result : report.results())
            out.append("| ").append(cell(result.checkId())).append(" | ").append(cell(result.type()))
                    .append(" | ").append(value(result.sourceValue())).append(" | ")
                    .append(value(result.targetValue())).append(" | ")
                    .append(result.passed() ? "PASS" : "FAIL").append(" | ")
                    .append(cell(result.error())).append(" |\n");
        if (!report.diagnostics().isEmpty()) {
            out.append("\n## Diagnostics\n\n");
            report.diagnostics().forEach(d -> out.append("- ").append(cell(d)).append("\n"));
        }
        out.append("\nOnly planner-generated scalar COUNT SELECT statements are executed. No DDL or data writes are performed. ")
                .append("Database accounts must independently be restricted to read-only privileges.\n");
        return out.toString();
    }

    private static String value(Long value) { return value == null ? "—" : value.toString(); }
    private static String cell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ").trim();
    }
}

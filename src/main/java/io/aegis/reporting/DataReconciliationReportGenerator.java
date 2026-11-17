package io.aegis.reporting;

import io.aegis.planning.DataReconciliationPlanner;

/** Markdown renderer for generated, non-executed source/target data validation SQL. */
public final class DataReconciliationReportGenerator {
    public String generate(DataReconciliationPlanner.Plan plan) {
        if (plan == null) throw new IllegalArgumentException("reconciliation plan is required");
        StringBuilder out = new StringBuilder("# Data Reconciliation Plan\n\n");
        out.append("**Source:** ").append(cell(plan.sourceStoreId())).append(" (")
                .append(cell(plan.sourceVendor())).append(")  \n");
        out.append("**Target:** ").append(cell(plan.targetStoreId())).append(" (")
                .append(cell(plan.targetVendor())).append(")  \n");
        out.append("**Mapped tables:** ").append(plan.mappedTables()).append("  \n");
        out.append("**Validation checks:** ").append(plan.checks().size()).append("\n\n");
        out.append("| Check | Type | Severity | Table | Expected result |\n");
        out.append("|---|---|---|---|---|\n");
        for (var check : plan.checks())
            out.append("| ").append(cell(check.id())).append(" | ").append(cell(check.type())).append(" | ")
                    .append(cell(check.severity())).append(" | ").append(cell(check.table())).append(" | ")
                    .append(cell(check.expectedResult())).append(" |\n");
        if (!plan.diagnostics().isEmpty()) {
            out.append("\n## Mapping and compatibility diagnostics\n\n");
            plan.diagnostics().forEach(diagnostic -> out.append("- ").append(cell(diagnostic)).append("\n"));
        }
        out.append("\n## Generated validation SQL\n");
        for (var check : plan.checks()) {
            out.append("\n### ").append(cell(check.id())).append("\n\n**Source**\n\n```sql\n")
                    .append(check.sourceSql()).append("\n```\n\n**Target**\n\n```sql\n")
                    .append(check.targetSql()).append("\n```\n");
        }
        out.append("\n## Limitations and safety gates\n\n");
        plan.limitations().forEach(limitation -> out.append("- ").append(cell(limitation)).append("\n"));
        out.append("\n**No SQL in this plan has been executed.** Run the generated checks only with read-only credentials, ")
                .append("after review, against the intended source and target databases.\n");
        return out.toString();
    }

    private static String cell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ").trim();
    }
}

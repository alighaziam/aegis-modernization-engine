package io.aegis.reporting;

import io.aegis.planning.ModernizationPlanner;

import java.util.Comparator;

/** Deterministic Markdown rendering for a user-approved modernization target profile. */
public final class MigrationPlanReportGenerator {
    public String generate(ModernizationPlanner.Plan plan) {
        if (plan == null) throw new IllegalArgumentException("migration plan is required");
        var target = plan.target();
        StringBuilder out = new StringBuilder("# Aegis Migration Plan\n\n");
        out.append("## Target profile\n\n");
        out.append("| Area | Target |\n|---|---|\n");
        row(out, "Java", Integer.toString(target.javaVersion()));
        row(out, "Framework", target.framework() + " " + target.frameworkVersion());
        row(out, "Database", target.database());
        row(out, "Frontend", target.frontend());
        row(out, "Deployment", target.deployment());

        out.append("\n## Architecture decisions\n\n");
        out.append("| Area | Target | Rationale | Confidence | Evidence |\n|---|---|---|---|---|\n");
        plan.decisions().stream().sorted(Comparator.comparing(ModernizationPlanner.Decision::area))
                .forEach(decision -> out.append("| ").append(cell(decision.area())).append(" | ")
                        .append(cell(decision.target())).append(" | ").append(cell(decision.rationale()))
                        .append(" | ").append(cell(decision.confidence())).append(" | ")
                        .append(cell(String.join(", ", decision.evidence()))).append(" |\n"));

        out.append("\n## Migration phases\n\n");
        out.append("| Order | Phase | Title | Description | Risk | Dependencies |\n");
        out.append("|---:|---|---|---|---|---|\n");
        plan.steps().stream().sorted(Comparator.comparingInt(ModernizationPlanner.MigrationStep::order))
                .forEach(step -> out.append("| ").append(step.order()).append(" | ")
                        .append(cell(step.id())).append(" | ").append(cell(step.title())).append(" | ")
                        .append(cell(step.description())).append(" | ").append(cell(step.risk())).append(" | ")
                        .append(cell(String.join(", ", step.dependsOn()))).append(" |\n"));

        out.append("\n## Safety gates\n\n");
        for (String gate : plan.safetyGates()) out.append("- ").append(cell(gate)).append("\n");
        out.append("\n**Important:** This is a deterministic planning artifact, not an executed migration. ")
                .append("Verify framework/runtime compatibility and obtain human approval before applying transformations.\n");
        return out.toString();
    }

    private static void row(StringBuilder out, String key, String value) {
        out.append("| ").append(cell(key)).append(" | ").append(cell(value)).append(" |\n");
    }
    private static String cell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r", " ").replace("\n", " ").trim();
    }
}

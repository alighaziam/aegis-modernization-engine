package io.aegis.planning;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.List;

/** Produces deterministic, explainable target architecture decisions and migration phases. */
public final class ModernizationPlanner {
    public Plan create(ProjectKnowledgeModel source, TargetProfile target) {
        List<Decision> decisions = new ArrayList<>();
        List<MigrationStep> steps = new ArrayList<>();
        int order = 1;
        decisions.add(new Decision("runtime", "Java " + target.javaVersion(),
                "Target runtime explicitly selected; validate library and bytecode compatibility.",
                "HIGH", List.of("target-profile")));
        decisions.add(new Decision("application-framework", target.framework() + " " + target.frameworkVersion(),
                "Framework target is user supplied; verify its Java compatibility matrix before adoption.",
                "HIGH", List.of("target-profile")));
        if (source.dataStores().stream().anyMatch(db -> db.vendor().equalsIgnoreCase("oracle"))
                && target.database().equalsIgnoreCase("postgresql")) {
            decisions.add(new Decision("database", "Oracle to PostgreSQL",
                    "Dialect, sequence, package, procedure and data-type differences require explicit review.",
                    "MEDIUM", List.of("source-upkm", "target-profile")));
        }
        steps.add(new MigrationStep(order++, "baseline", "Capture baseline",
                "Record build, tests, runtime behavior, schema and operational metrics.", "LOW", List.of()));
        steps.add(new MigrationStep(order++, "compatibility", "Resolve compatibility",
                "Inventory dependencies and map each library to a target-compatible version.", "HIGH", List.of("baseline")));
        steps.add(new MigrationStep(order++, "data", "Migrate data layer",
                "Create reversible schema changes, rehearse data migration and reconcile row counts.", "HIGH", List.of("compatibility")));
        steps.add(new MigrationStep(order++, "application", "Modernize application modules",
                "Transform one bounded module at a time and preserve contract tests.", "HIGH", List.of("data")));
        steps.add(new MigrationStep(order++, "cutover", "Validate and cut over",
                "Run regression, security, performance and operational checks before traffic switch.", "HIGH", List.of("application")));
        return new Plan(target, decisions, steps,
                List.of("Do not apply transformations until compatibility and human review gates pass.",
                        "Keep the source system available until rollback criteria are satisfied."));
    }

    public record TargetProfile(int javaVersion, String framework, String frameworkVersion, String database,
                                String frontend, String deployment) {
        public TargetProfile {
            if (javaVersion < 8) throw new IllegalArgumentException("javaVersion must be at least 8");
            if (blank(framework) || blank(frameworkVersion) || blank(database))
                throw new IllegalArgumentException("framework, frameworkVersion and database are required");
        }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
    public record Decision(String area, String target, String rationale, String confidence, List<String> evidence) {
        public Decision { evidence=List.copyOf(evidence); }
    }
    public record MigrationStep(int order, String id, String title, String description,
                                String risk, List<String> dependsOn) {
        public MigrationStep { dependsOn=List.copyOf(dependsOn); }
    }
    public record Plan(TargetProfile target, List<Decision> decisions, List<MigrationStep> steps,
                       List<String> safetyGates) {
        public Plan { decisions=List.copyOf(decisions); steps=List.copyOf(steps); safetyGates=List.copyOf(safetyGates); }
    }
}

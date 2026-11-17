package io.aegis.planning;

import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.analysis.DependencyGraphAnalyzer;
import io.aegis.parser.sql.DatabaseDialectRegistry;

import java.util.ArrayList;
import java.util.List;

/** Produces deterministic, explainable target architecture decisions and migration phases. */
public final class ModernizationPlanner {
    public Plan create(ProjectKnowledgeModel source, TargetProfile target) {
        if (source == null) throw new IllegalArgumentException("source UPKM is required");
        if (target == null) throw new IllegalArgumentException("target profile is required");
        List<Decision> decisions = new ArrayList<>();
        List<MigrationStep> steps = new ArrayList<>();
        int order = 1;
        decisions.add(new Decision("runtime", "Java " + target.javaVersion(),
                "Target runtime explicitly selected; validate library and bytecode compatibility.",
                "HIGH", List.of("target-profile")));
        decisions.add(new Decision("application-framework", target.framework() + " " + target.frameworkVersion(),
                "Framework target is user supplied; verify its Java compatibility matrix before adoption.",
                "HIGH", List.of("target-profile")));
        boolean oracleSource = source.dataStores().stream()
                .anyMatch(db -> db.vendor().equalsIgnoreCase("oracle"));
        boolean registeredTarget = new DatabaseDialectRegistry().find(target.database()).isPresent();
        String databaseRationale = oracleSource && target.database().equalsIgnoreCase("postgresql")
                ? "Oracle-to-PostgreSQL dialect, sequence, package, procedure and data-type differences require explicit review."
                : registeredTarget
                    ? "Target database was explicitly selected; use only dialect capabilities declared and tested by the registry."
                    : "Custom database target was explicitly selected; a dedicated tested dialect adapter is required before automated transformation.";
        decisions.add(new Decision("database", target.database(), databaseRationale,
                registeredTarget ? "HIGH" : "LOW", List.of("target-profile", "database-dialect-registry")));
        if (!blank(target.frontend()))
            decisions.add(new Decision("frontend", target.frontend(),
                    "Frontend target is user supplied; validate API contract compatibility and migration coverage.",
                    "HIGH", List.of("target-profile")));
        if (!blank(target.deployment()))
            decisions.add(new Decision("deployment", target.deployment(),
                    "Deployment target is user supplied; validate runtime, network, secrets and observability requirements.",
                    "HIGH", List.of("target-profile")));

        int tableCount = source.dataStores().stream().flatMap(store -> store.schemas().stream())
                .mapToInt(schema -> schema.tables().size()).sum();
        int inferredEntities = (int) source.logicalEntities().stream()
                .filter(entity -> entity.status() == ProjectKnowledgeModel.FactStatus.INFERRED).count();
        decisions.add(new Decision("source-inventory",
                source.components().size() + " components, " + source.technologies().size()
                        + " technologies, " + source.apiEndpoints().size() + " API endpoints",
                "Planning is based on the current UPKM snapshot; missing inputs must be added before detailed estimation.",
                source.components().isEmpty() ? "LOW" : "MEDIUM", List.of("upkm-snapshot")));
        decisions.add(new Decision("data-model",
                source.dataStores().size() + " physical stores, " + tableCount + " tables, "
                        + source.logicalEntities().size() + " logical entities",
                "Physical schema and logical business concepts remain separate; reconcile inferred entities before migration.",
                inferredEntities > 0 ? "LOW" : "MEDIUM", List.of("upkm-data-model")));
        decisions.add(new Decision("api-contracts", source.apiEndpoints().size() + " discovered endpoints",
                source.apiEndpoints().isEmpty()
                        ? "No API contracts were discovered; obtain endpoint specifications before contract migration."
                        : "Preserve discovered endpoint behavior with consumer/provider contract tests.",
                source.apiEndpoints().isEmpty() ? "LOW" : "MEDIUM", List.of("upkm-api-inventory")));

        DependencyGraphAnalyzer.GraphReport graph = new DependencyGraphAnalyzer().analyze(source);
        decisions.add(new Decision("dependency-architecture", graph.cycles().size() + " dependency cycles",
                graph.cycles().isEmpty()
                        ? "No cycles were detected among discovered components; runtime and reflection dependencies remain unverified."
                        : "Break or explicitly isolate dependency cycles before extracting modules or changing deployment boundaries.",
                graph.cycles().isEmpty() ? "MEDIUM" : "HIGH", List.of("upkm-dependency-graph")));
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
        List<String> safetyGates = new ArrayList<>(List.of(
                "Do not apply transformations until compatibility and human review gates pass.",
                "Keep the source system available until rollback criteria are satisfied.",
                "Verify exact framework/runtime compatibility; the planner does not certify version support."));
        if (!graph.cycles().isEmpty())
            safetyGates.add("Resolve or explicitly accept discovered dependency cycles before module extraction.");
        if (inferredEntities > 0)
            safetyGates.add("Confirm inferred logical entities and relationships with business/data owners before data migration.");
        return new Plan(target, decisions, steps, safetyGates);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

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

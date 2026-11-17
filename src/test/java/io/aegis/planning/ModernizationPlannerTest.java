package io.aegis.planning;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModernizationPlannerTest {
    @Test void createsOrderedMigrationPhasesAndExplicitSafetyGates() {
        var source = ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo","Demo",null,"retail"));
        var target = new ModernizationPlanner.TargetProfile(25,"Spring Boot","4.0.0","PostgreSQL","React","Kubernetes");
        var plan = new ModernizationPlanner().create(source,target);
        assertEquals(5, plan.steps().size());
        assertTrue(plan.steps().get(0).order() < plan.steps().get(4).order());
        assertFalse(plan.safetyGates().isEmpty());
        assertTrue(plan.decisions().stream().anyMatch(d -> d.area().equals("database") && d.target().equals("PostgreSQL")));
    }

    @Test void acceptsAnyExplicitDatabaseTargetAndMarksUnknownAdaptersForReview() {
        var source = ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, "retail"));
        var target = new ModernizationPlanner.TargetProfile(25, "Spring Boot", "4.0.0", "Teradata", "React", "Kubernetes");
        var plan = new ModernizationPlanner().create(source, target);
        var database = plan.decisions().stream().filter(d -> d.area().equals("database")).findFirst().orElseThrow();
        assertEquals("Teradata", database.target());
        assertEquals("LOW", database.confidence());
        assertTrue(database.rationale().contains("dedicated tested dialect adapter"));
    }
    @Test
    void addsHighRiskGatesForCyclesAndInferredLogicalEntities() {
        var project = new ProjectKnowledgeModel.Project("cycle-demo", "Cycle Demo", null, null);
        var components = List.of(
                new ProjectKnowledgeModel.ApplicationComponent("A", "A", "JAVA_CLASS", "A.java",
                        ProjectKnowledgeModel.FactStatus.OBSERVED),
                new ProjectKnowledgeModel.ApplicationComponent("B", "B", "JAVA_CLASS", "B.java",
                        ProjectKnowledgeModel.FactStatus.OBSERVED));
        var dependencies = List.of(
                new ProjectKnowledgeModel.Dependency("A", "B", "IMPORT", ProjectKnowledgeModel.FactStatus.OBSERVED, List.of()),
                new ProjectKnowledgeModel.Dependency("B", "A", "IMPORT", ProjectKnowledgeModel.FactStatus.OBSERVED, List.of()));
        var inferred = new ProjectKnowledgeModel.LogicalEntity("Customer", "Customer", List.of(), List.of(),
                ProjectKnowledgeModel.FactStatus.INFERRED);
        var source = new ProjectKnowledgeModel(project, List.of(), List.of(), components, List.of(),
                List.of(inferred), List.of(), dependencies, List.of(), java.time.Instant.now(), "1.0");
        var target = new ModernizationPlanner.TargetProfile(25, "Spring Boot", "4.0.0",
                "PostgreSQL", "React", "Kubernetes");

        var plan = new ModernizationPlanner().create(source, target);

        var dependencyDecision = plan.decisions().stream()
                .filter(d -> d.area().equals("dependency-architecture")).findFirst().orElseThrow();
        assertEquals("HIGH", dependencyDecision.confidence());
        assertTrue(plan.safetyGates().stream().anyMatch(g -> g.contains("dependency cycles")));
        assertTrue(plan.safetyGates().stream().anyMatch(g -> g.contains("inferred logical entities")));
    }

    @Test
    void rejectsMissingSourceOrTarget() {
        var planner = new ModernizationPlanner();
        assertThrows(IllegalArgumentException.class, () -> planner.create(null,
                new ModernizationPlanner.TargetProfile(25, "Spring Boot", "4.0.0",
                        "PostgreSQL", null, null)));
        assertThrows(IllegalArgumentException.class, () -> planner.create(
                ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null)), null));
    }

}

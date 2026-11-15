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
}

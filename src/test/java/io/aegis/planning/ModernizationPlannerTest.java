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
    }
}

package io.aegis.reporting;

import io.aegis.planning.ModernizationPlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MigrationPlanReportGeneratorTest {
    @Test
    void rendersTargetDecisionsPhasesAndSafetyGatesDeterministically() {
        var target = new ModernizationPlanner.TargetProfile(25, "Spring Boot", "4.0.0",
                "PostgreSQL", "React", "Kubernetes");
        var plan = new ModernizationPlanner().create(
                io.aegis.domain.ProjectKnowledgeModel.empty(
                        new io.aegis.domain.ProjectKnowledgeModel.Project("demo", "Demo", null, null)), target);

        String report = new MigrationPlanReportGenerator().generate(plan);

        assertTrue(report.contains("Spring Boot 4.0.0"));
        assertTrue(report.contains("PostgreSQL"));
        assertTrue(report.contains("Migration phases"));
        assertTrue(report.contains("Capture baseline"));
        assertTrue(report.contains("human approval"));
        assertEquals(report, new MigrationPlanReportGenerator().generate(plan));
    }
}

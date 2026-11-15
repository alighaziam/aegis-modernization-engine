package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphAnalyzerTest {
    @Test
    void detectsStronglyConnectedComponentsAndSeparatesExternalDependencies() {
        ProjectKnowledgeModel project = new ProjectKnowledgeModel(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(),
                List.of(
                        new ProjectKnowledgeModel.ApplicationComponent("A", "A", "JAVA_CLASS", null, null),
                        new ProjectKnowledgeModel.ApplicationComponent("B", "B", "JAVA_CLASS", null, null),
                        new ProjectKnowledgeModel.ApplicationComponent("C", "C", "JAVA_CLASS", null, null)),
                List.of(), List.of(), List.of(),
                List.of(
                        new ProjectKnowledgeModel.Dependency("A", "B", "JAVA_IMPORT", null, List.of()),
                        new ProjectKnowledgeModel.Dependency("B", "A", "JAVA_IMPORT", null, List.of()),
                        new ProjectKnowledgeModel.Dependency("B", "java.util.List", "JAVA_IMPORT", null, List.of())),
                List.of(), null, "1.0");

        DependencyGraphAnalyzer.GraphReport report =
                new DependencyGraphAnalyzer().analyze(project);

        assertEquals(3, report.nodeCount());
        assertEquals(2, report.internalEdgeCount());
        assertEquals(1, report.externalEdgeCount());
        assertEquals(1, report.cycles().size());
        assertEquals(List.of("A", "B"), report.cycles().get(0).componentIds());
    }

    @Test
    void detectsSelfDependencyAsCycle() {
        ProjectKnowledgeModel project = new ProjectKnowledgeModel(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(),
                List.of(new ProjectKnowledgeModel.ApplicationComponent("A", "A", "JAVA_CLASS", null, null)),
                List.of(), List.of(), List.of(),
                List.of(new ProjectKnowledgeModel.Dependency("A", "A", "JAVA_IMPORT", null, List.of())),
                List.of(), null, "1.0");

        assertEquals(1, new DependencyGraphAnalyzer().analyze(project).cycles().size());
    }
}

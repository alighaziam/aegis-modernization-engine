package io.aegis.discovery;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSourceScannerTest {
    @TempDir Path temp;

    @Test
    void scansWholeProjectDeterministicallyAndMergesJavaAndBuildFindings() throws Exception {
        Path root = temp.resolve("project");
        Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Files.createDirectories(root.resolve("module/src/main/java/com/acme"));
        Files.createDirectories(root.resolve("target/generated"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><properties><maven.compiler.release>21</maven.compiler.release></properties>
                <parent><artifactId>spring-boot-starter-parent</artifactId><version>3.4.1</version></parent></project>
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/OrderService.java"), """
                package com.acme;
                import java.util.List;
                public class OrderService { public List<String> find() { return List.of(); } }
                """);
        Files.writeString(root.resolve("module/src/main/java/com/acme/Customer.java"), """
                package com.acme;
                public record Customer(String id) {}
                """);
        Files.writeString(root.resolve("target/generated/Generated.java"), "class MustBeIgnored {}");

        var report = new ProjectSourceScanner().scan(root, ProjectKnowledgeModel.empty(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, null)));

        assertEquals(3, report.discoveredFiles());
        assertEquals(2, report.javaFiles());
        assertEquals(2, report.javaFilesAnalyzed());
        assertEquals(1, report.buildDescriptors());
        assertEquals(2, report.declaredTypes());
        assertTrue(report.knowledgeModel().components().stream()
                .anyMatch(component -> component.id().equals("com.acme.OrderService")));
        assertTrue(report.knowledgeModel().technologies().stream()
                .anyMatch(technology -> technology.name().equals("Java") && "21".equals(technology.version())));
        assertTrue(report.knowledgeModel().technologies().stream()
                .anyMatch(technology -> technology.name().equals("Spring Boot") && "3.4.1".equals(technology.version())));
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void rejectsNonDirectoryRoots() throws Exception {
        Path file = temp.resolve("not-a-directory.txt");
        Files.writeString(file, "x");
        assertThrows(IllegalArgumentException.class, () -> new ProjectSourceScanner().scan(file,
                ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null))));
    }
}

package io.aegis.parser.java;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceToKnowledgeMapperTest {
    @Test
    void mapsJavaTypesAndDeclaredImportsIntoProjectKnowledgeModel() throws Exception {
        ProjectKnowledgeModel project = ProjectKnowledgeModel.empty(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, "commerce"));
        JavaSourceAnalyzer.AnalysisResult analysis = new JavaSourceAnalyzer().analyze("""
                package com.acme;
                import java.util.List;
                public class CustomerService {}
                """, "CustomerService.java");

        ProjectKnowledgeModel mapped = new JavaSourceToKnowledgeMapper()
                .merge(project, List.of(analysis));

        assertTrue(mapped.components().stream().anyMatch(c ->
                c.id().equals("com.acme.CustomerService") && "JAVA_CLASS".equals(c.type())));
        assertTrue(mapped.dependencies().stream().anyMatch(d ->
                d.from().equals("com.acme.CustomerService")
                        && d.to().equals("java.util.List")
                        && "JAVA_IMPORT".equals(d.kind())));
    }
}

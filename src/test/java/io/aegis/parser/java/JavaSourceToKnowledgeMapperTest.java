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
    @Test
    void resolvesInternalImportsAfterAllProjectTypesAreDiscovered() throws Exception {
        var analyzer = new JavaSourceAnalyzer();
        var first = analyzer.analyze("""
                package com.acme;
                import com.acme.BillingService;
                public class OrderService {}
                """, "OrderService.java");
        var second = analyzer.analyze("""
                package com.acme;
                import com.acme.OrderService;
                public class BillingService {}
                """, "BillingService.java");

        ProjectKnowledgeModel mapped = new JavaSourceToKnowledgeMapper().merge(
                ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null)),
                List.of(first, second));

        assertTrue(mapped.dependencies().stream().anyMatch(d ->
                d.from().equals("com.acme.OrderService")
                        && d.to().equals("com.acme.BillingService")
                        && d.kind().equals("JAVA_INTERNAL_IMPORT")));
        assertTrue(mapped.dependencies().stream().anyMatch(d ->
                d.from().equals("com.acme.BillingService")
                        && d.to().equals("com.acme.OrderService")
                        && d.kind().equals("JAVA_INTERNAL_IMPORT")));
        var graph = new io.aegis.analysis.DependencyGraphAnalyzer().analyze(mapped);
        assertEquals(1, graph.cycles().size());
        assertEquals(2, graph.cycles().get(0).componentIds().size());
    }

    @Test
    void resolvesWildcardImportsToDiscoveredTypes() throws Exception {
        var analyzer = new JavaSourceAnalyzer();
        var consumer = analyzer.analyze("""
                package com.acme.app;
                import com.acme.domain.*;
                public class Consumer {}
                """, "Consumer.java");
        var model = new JavaSourceToKnowledgeMapper().merge(
                ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null)),
                List.of(consumer, analyzer.analyze("package com.acme.domain; public class Customer {}", "Customer.java")));

        assertTrue(model.dependencies().stream().anyMatch(d ->
                d.from().equals("com.acme.app.Consumer")
                        && d.to().equals("com.acme.domain.Customer")
                        && d.kind().equals("JAVA_INTERNAL_IMPORT")));
    }

}

package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiContractConsistencyAnalyzerTest {
    @Test
    void matchesSpringRoutesToOpenApiMethodsAndNormalizesPathVariableNames() {
        var spring = new SpringApiAnalyzer.Analysis(List.of(
                new SpringApiAnalyzer.EndpointMapping("com.acme.CustomerController", "get", "GET",
                        "/customers/{customerId}", "CustomerController.java", 10, "GetMapping"),
                new SpringApiAnalyzer.EndpointMapping("com.acme.CustomerController", "create", "POST",
                        "/customers", "CustomerController.java", 20, "PostMapping")),
                List.of(), 1, 100, List.of());
        var specs = List.of(
                new ProjectKnowledgeModel.ApiEndpoint("list", "GET", "/customers/{id}", "getCustomer",
                        "openapi.yaml", ProjectKnowledgeModel.FactStatus.OBSERVED),
                new ProjectKnowledgeModel.ApiEndpoint("create", "POST", "/customers", "createCustomer",
                        "openapi.yaml", ProjectKnowledgeModel.FactStatus.OBSERVED));

        var report = new ApiContractConsistencyAnalyzer().analyze(spring, specs);

        assertTrue(report.available());
        assertEquals(2, report.matched());
        assertEquals(0, report.springOnly());
        assertEquals(0, report.openApiOnly());
        assertEquals(0, report.warningCount());
    }

    @Test
    void reportsSourceOnlyContractOnlyAndMethodMismatches() {
        var spring = new SpringApiAnalyzer.Analysis(List.of(
                new SpringApiAnalyzer.EndpointMapping("com.acme.Controller", "read", "GET",
                        "/items", "Controller.java", 1, "GetMapping"),
                new SpringApiAnalyzer.EndpointMapping("com.acme.Controller", "delete", "DELETE",
                        "/items/{id}", "Controller.java", 2, "DeleteMapping")),
                List.of(), 1, 100, List.of());
        var specs = List.of(
                new ProjectKnowledgeModel.ApiEndpoint("one", "POST", "/items", "createItem",
                        "openapi.yaml", ProjectKnowledgeModel.FactStatus.OBSERVED),
                new ProjectKnowledgeModel.ApiEndpoint("two", "GET", "/other", "other",
                        "openapi.yaml", ProjectKnowledgeModel.FactStatus.OBSERVED));

        var report = new ApiContractConsistencyAnalyzer().analyze(spring, specs);

        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("API_METHOD_MISMATCH")));
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("SPRING_ENDPOINT_NOT_IN_OPENAPI")));
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("OPENAPI_ENDPOINT_NOT_IN_SPRING_SOURCE")));
        assertEquals(2, report.springOnly());
        assertEquals(2, report.openApiOnly());
    }

    @Test
    void marksComparisonUnavailableWhenEitherInventoryIsEmpty() {
        var spring = new SpringApiAnalyzer.Analysis(List.of(), List.of(), 0, 0, List.of());
        var report = new ApiContractConsistencyAnalyzer().analyze(spring, List.of());
        assertFalse(report.available());
        assertEquals(0, report.warningCount());
    }
}

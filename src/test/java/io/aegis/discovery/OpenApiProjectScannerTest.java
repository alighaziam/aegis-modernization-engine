package io.aegis.discovery;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiProjectScannerTest {
    @TempDir Path temp;

    @Test
    void discoversMultipleOpenApiContractsAndIgnoresUnrelatedGeneratedFiles() throws Exception {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root.resolve("contracts"));
        Files.createDirectories(root.resolve("module"));
        Files.createDirectories(root.resolve("target/generated"));
        Files.writeString(root.resolve("contracts/customers.yaml"), """
                openapi: 3.0.3
                info:
                  title: Customer API
                  version: 1.0.0
                paths:
                  /customers:
                    get:
                      operationId: listCustomers
                components:
                  schemas:
                    Customer:
                      type: object
                      required: [id]
                      properties:
                        id:
                          type: string
                """);
        Files.writeString(root.resolve("module/orders.json"), """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "Order API", "version": "1.0.0"},
                  "paths": {"/orders": {"post": {"operationId": "createOrder"}}}
                }
                """);
        Files.writeString(root.resolve("module/config.json"), "{\"feature\": true}");
        Files.writeString(root.resolve("target/generated/ignored.yaml"), "openapi: 3.0.0");

        var base = ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null));
        var report = new OpenApiProjectScanner().scan(root, base);

        assertEquals(3, report.candidatesDiscovered());
        assertEquals(2, report.specificationsAnalyzed());
        assertEquals(2, report.endpointsDiscovered());
        assertEquals(1, report.schemasDiscovered());
        assertEquals("Customer", report.schemas().get(0).name());
        assertEquals("contracts/customers.yaml", report.specifications().get(0).sourceName());
        assertEquals(2, report.knowledgeModel().apiEndpoints().size());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void reportsMalformedOpenApiDocumentWithoutAbortingWorkspaceScan() throws Exception {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root);
        Files.writeString(root.resolve("broken.yaml"), "openapi: [invalid");
        var base = ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null));

        var report = new OpenApiProjectScanner().scan(root, base);

        assertEquals(1, report.specificationsAnalyzed());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.contains("OPENAPI_PARSE_ERROR")));
    }
}

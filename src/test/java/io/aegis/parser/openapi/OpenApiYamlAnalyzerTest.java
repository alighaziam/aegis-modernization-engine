package io.aegis.parser.openapi;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenApiYamlAnalyzerTest {
    @Test void readsYamlPathsAndSchemasWithoutResolvingExternalReferences() {
        String yaml = """
                openapi: 3.0.3
                info:
                  title: Orders API
                paths:
                  /orders:
                    get:
                      operationId: listOrders
                components:
                  schemas:
                    Order:
                      type: object
                      required: [id]
                      properties:
                        id:
                          type: string
                """;
        var result = new OpenApiYamlAnalyzer().analyze(yaml, "openapi.yaml");
        assertEquals("3.0.3", result.openApiVersion());
        assertEquals("Orders API", result.title());
        assertEquals(1, result.endpoints().size());
        assertEquals("GET", result.endpoints().get(0).method());
        assertTrue(result.schemas().get(0).properties().get(0).required());
        assertTrue(result.diagnostics().isEmpty());
    }
}

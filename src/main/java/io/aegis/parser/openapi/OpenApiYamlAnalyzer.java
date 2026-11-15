package io.aegis.parser.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses OpenAPI JSON/YAML documents without executing references or remote resolvers. */
public final class OpenApiYamlAnalyzer {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    public Analysis analyze(String document, String sourceName) {
        if (document == null || document.isBlank()) throw new IllegalArgumentException("OpenAPI document is required");
        List<String> diagnostics = new ArrayList<>();
        try {
            JsonNode root = (sourceName != null && sourceName.toLowerCase(Locale.ROOT).endsWith(".json"))
                    ? JSON.readTree(document) : YAML.readTree(document);
            if (root == null || !root.path("openapi").isTextual()) {
                diagnostics.add("Missing OpenAPI version; document may not be an OpenAPI 3.x contract.");
            }
            List<Endpoint> endpoints = new ArrayList<>();
            JsonNode paths = root == null ? null : root.path("paths");
            if (paths != null && paths.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
                while (pathIterator.hasNext()) {
                    Map.Entry<String, JsonNode> path = pathIterator.next();
                    Iterator<Map.Entry<String, JsonNode>> methods = path.getValue().fields();
                    while (methods.hasNext()) {
                        Map.Entry<String, JsonNode> method = methods.next();
                        String verb = method.getKey().toUpperCase(Locale.ROOT);
                        if (!List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD","TRACE").contains(verb)) continue;
                        JsonNode operation = method.getValue();
                        endpoints.add(new Endpoint(verb, path.getKey(),
                                text(operation, "operationId"), text(operation, "summary"),
                                sourceName, "OBSERVED"));
                    }
                }
            } else diagnostics.add("OpenAPI paths object is missing.");
            List<Schema> schemas = new ArrayList<>();
            JsonNode schemaNodes = root == null ? null : root.path("components").path("schemas");
            if (schemaNodes != null && schemaNodes.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> iterator = schemaNodes.fields();
                while (iterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = iterator.next();
                    List<SchemaProperty> properties = new ArrayList<>();
                    JsonNode required = entry.getValue().path("required");
                    JsonNode props = entry.getValue().path("properties");
                    if (props.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> p = props.fields();
                        while (p.hasNext()) {
                            Map.Entry<String, JsonNode> property = p.next();
                            properties.add(new SchemaProperty(property.getKey(), text(property.getValue(), "type"),
                                    contains(required, property.getKey()), text(property.getValue(), "description")));
                        }
                    }
                    schemas.add(new Schema(entry.getKey(), text(entry.getValue(), "type"), properties));
                }
            }
            return new Analysis(text(root, "openapi"), text(root, "info") == null ? null : text(root.path("info"), "title"),
                    endpoints, schemas, diagnostics);
        } catch (Exception e) {
            return new Analysis(null, null, List.of(), List.of(),
                    List.of("OPENAPI_PARSE_ERROR: " + e.getMessage()));
        }
    }

    public ProjectKnowledgeModel merge(ProjectKnowledgeModel project, Analysis analysis) {
        List<ProjectKnowledgeModel.ApiEndpoint> mapped = new ArrayList<>(project.apiEndpoints());
        for (Endpoint endpoint : analysis.endpoints()) {
            String id = endpoint.method() + " " + endpoint.path();
            if (mapped.stream().noneMatch(existing -> existing.id().equals(id)))
                mapped.add(new ProjectKnowledgeModel.ApiEndpoint(id, endpoint.method(), endpoint.path(),
                        endpoint.operationId(), endpoint.sourceName(), ProjectKnowledgeModel.FactStatus.OBSERVED));
        }
        return new ProjectKnowledgeModel(project.project(), project.artifacts(), project.technologies(),
                project.components(), project.dataStores(), project.logicalEntities(), mapped,
                project.dependencies(), project.businessRules(), project.analyzedAt(), project.modelVersion());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }
    private static boolean contains(JsonNode array, String value) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode item : array) if (item.asText().equals(value)) return true;
        return false;
    }

    public record Analysis(String openApiVersion, String title, List<Endpoint> endpoints,
                           List<Schema> schemas, List<String> diagnostics) {
        public Analysis { endpoints=List.copyOf(endpoints); schemas=List.copyOf(schemas); diagnostics=List.copyOf(diagnostics); }
    }
    public record Endpoint(String method, String path, String operationId, String summary,
                           String sourceName, String status) {}
    public record Schema(String name, String type, List<SchemaProperty> properties) {
        public Schema { properties=List.copyOf(properties); }
    }
    public record SchemaProperty(String name, String type, boolean required, String description) {}
}

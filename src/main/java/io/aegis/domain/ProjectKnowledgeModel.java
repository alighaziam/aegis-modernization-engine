package io.aegis.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Canonical parser-independent model. Physical, logical and target models stay distinct. */
public record ProjectKnowledgeModel(
        Project project,
        List\u003cSourceArtifact\u003e artifacts,
        List\u003cTechnology\u003e technologies,
        List\u003cApplicationComponent\u003e components,
        List\u003cDataStore\u003e dataStores,
        List\u003cLogicalEntity\u003e logicalEntities,
        List\u003cApiEndpoint\u003e apiEndpoints,
        List\u003cDependency\u003e dependencies,
        List\u003cBusinessRule\u003e businessRules,
        Instant analyzedAt,
        String modelVersion) {

    public ProjectKnowledgeModel {
        Objects.requireNonNull(project, "project");
        artifacts = safe(artifacts); technologies = safe(technologies); components = safe(components);
        dataStores = safe(dataStores); logicalEntities = safe(logicalEntities); apiEndpoints = safe(apiEndpoints);
        dependencies = safe(dependencies); businessRules = safe(businessRules);
        analyzedAt = analyzedAt == null ? Instant.now() : analyzedAt;
        modelVersion = modelVersion == null ? "1.0" : modelVersion;
    }

    private static \u003cT\u003e List\u003cT\u003e safe(List\u003cT\u003e value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    public static ProjectKnowledgeModel empty(Project project) {
        return new ProjectKnowledgeModel(project, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), Instant.now(), "1.0");
    }

    public enum FactStatus { OBSERVED, USER_CONFIRMED, INFERRED, UNKNOWN, CONFLICTED }
    public enum Layer { SOURCE, TARGET, DISCOVERED }
    public enum ConstraintType { PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK, UNKNOWN }
    public enum RelationshipKind { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY, UNKNOWN }

    public record Project(String id, String name, String description, String businessDomain) {
        public Project { required(id, "project.id"); required(name, "project.name"); }
    }

    public record SourceArtifact(String artifactId, String category, String format, String origin,
                                 String location, String sha256, long sizeBytes) {
        public SourceArtifact {
            required(artifactId, "artifactId"); required(category, "category");
            required(format, "format"); required(origin, "origin");
            if (sizeBytes \u003c 0) throw new IllegalArgumentException("sizeBytes cannot be negative");
        }
    }

    public record Technology(String name, String version, String role, Layer layer) {
        public Technology { required(name, "technology.name"); layer = layer == null ? Layer.SOURCE : layer; }
    }

    public record ApplicationComponent(String id, String name, String type, String sourceLocator, FactStatus status) {
        public ApplicationComponent {
            required(id, "component.id"); required(name, "component.name");
            status = status == null ? FactStatus.OBSERVED : status;
        }
    }

    /** Physical database-specific representation. */
    public record DataStore(String id, String vendor, String version, List\u003cDbSchema\u003e schemas) {
        public DataStore { required(id, "dataStore.id"); required(vendor, "dataStore.vendor"); schemas = safe(schemas); }
    }
    public record DbSchema(String name, List\u003cDbTable\u003e tables) {
        public DbSchema { required(name, "schema.name"); tables = safe(tables); }
    }
    public record DbTable(String name, List\u003cDbColumn\u003e columns, List\u003cDbConstraint\u003e constraints,
                          List\u003cDbIndex\u003e indexes, String sourceLocator) {
        public DbTable { required(name, "table.name"); columns = safe(columns); constraints = safe(constraints); indexes = safe(indexes); }
    }
    public record DbColumn(String name, String dataType, Integer length, Integer precision, Integer scale,
                           boolean nullable, String defaultExpression, String sourceLocator) {
        public DbColumn { required(name, "column.name"); required(dataType, "column.dataType"); }
    }
    public record DbConstraint(String name, ConstraintType type, List\u003cString\u003e columns,
                               String referencedTable, List\u003cString\u003e referencedColumns, String sourceLocator) {
        public DbConstraint { type = type == null ? ConstraintType.UNKNOWN : type; columns = safe(columns); referencedColumns = safe(referencedColumns); }
    }
    public record DbIndex(String name, List\u003cString\u003e columns, boolean unique, String sourceLocator) {
        public DbIndex { required(name, "index.name"); columns = safe(columns); }
    }

    /** Logical, database-independent business representation. */
    public record LogicalEntity(String id, String name, List\u003cLogicalAttribute\u003e attributes,
                                List\u003cLogicalRelationship\u003e relationships, FactStatus status) {
        public LogicalEntity { required(id, "entity.id"); required(name, "entity.name"); attributes = safe(attributes); relationships = safe(relationships); status = status == null ? FactStatus.UNKNOWN : status; }
    }
    public record LogicalAttribute(String name, String businessType, boolean required, String description, FactStatus status) {
        public LogicalAttribute { ProjectKnowledgeModel.required(name, "attribute.name"); status = status == null ? FactStatus.UNKNOWN : status; }
    }
    public record LogicalRelationship(String id, String fromEntity, String toEntity, RelationshipKind cardinality,
                                      FactStatus status, List\u003cString\u003e evidenceIds) {
        public LogicalRelationship {
            required(id, "relationship.id"); required(fromEntity, "relationship.fromEntity"); required(toEntity, "relationship.toEntity");
            cardinality = cardinality == null ? RelationshipKind.UNKNOWN : cardinality;
            status = status == null ? FactStatus.INFERRED : status; evidenceIds = safe(evidenceIds);
        }
    }
    public record ApiEndpoint(String id, String method, String path, String operationId, String sourceLocator, FactStatus status) {
        public ApiEndpoint { required(id, "endpoint.id"); required(method, "endpoint.method"); required(path, "endpoint.path"); status = status == null ? FactStatus.OBSERVED : status; }
    }
    public record Dependency(String from, String to, String kind, FactStatus status, List\u003cString\u003e evidenceIds) {
        public Dependency { required(from, "dependency.from"); required(to, "dependency.to"); status = status == null ? FactStatus.OBSERVED : status; evidenceIds = safe(evidenceIds); }
    }
    public record BusinessRule(String id, String statement, String sourceLocator, FactStatus status) {
        public BusinessRule { required(id, "businessRule.id"); required(statement, "businessRule.statement"); status = status == null ? FactStatus.UNKNOWN : status; }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}

package io.aegis.reporting;

import io.aegis.analysis.DependencyGraphAnalyzer;
import io.aegis.analysis.DataLineageAnalyzer;
import io.aegis.analysis.JpaMappingAnalyzer;
import io.aegis.domain.ProjectKnowledgeModel;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Deterministic Markdown rendering of the evidence-backed project knowledge snapshot. */
public final class ProjectReportGenerator {
    public String generate(ProjectKnowledgeModel model) {
        return generate(model, null);
    }

    public String generate(ProjectKnowledgeModel model, JpaMappingAnalyzer.MappingReport jpaMappings) {
        Objects.requireNonNull(model, "model");
        StringBuilder out = new StringBuilder();
        var project = model.project();
        var graph = new DependencyGraphAnalyzer().analyze(model);
        var lineage = new DataLineageAnalyzer().analyze(model);
        int tableCount = model.dataStores().stream().flatMap(store -> store.schemas().stream())
                .mapToInt(schema -> schema.tables().size()).sum();
        int columnCount = model.dataStores().stream().flatMap(store -> store.schemas().stream())
                .flatMap(schema -> schema.tables().stream()).mapToInt(table -> table.columns().size()).sum();

        out.append("# Aegis Modernization Report\n\n");
        out.append("## Project overview\n\n");
        out.append("| Property | Value |\n|---|---|\n");
        row(out, "Project", project.name());
        row(out, "Project ID", project.id());
        row(out, "Business domain", project.businessDomain());
        row(out, "Description", project.description());
        row(out, "Analysis timestamp", model.analyzedAt().toString());
        row(out, "UPKM version", model.modelVersion());

        out.append("\n## Snapshot metrics\n\n");
        out.append("| Metric | Count |\n|---|---:|\n");
        metric(out, "Source artifacts", model.artifacts().size());
        metric(out, "Technology findings", model.technologies().size());
        metric(out, "Application components", model.components().size());
        metric(out, "Dependencies", model.dependencies().size());
        metric(out, "Dependency cycles", graph.cycles().size());
        metric(out, "Data stores", model.dataStores().size());
        metric(out, "Physical tables", tableCount);
        metric(out, "Physical columns", columnCount);
        metric(out, "Declared FK lineage edges", lineage.edges().size());
        if (jpaMappings != null) {
            metric(out, "JPA entities", jpaMappings.entities().size());
            metric(out, "JPA relationships", jpaMappings.relationships().size());
        }
        metric(out, "Logical entities", model.logicalEntities().size());
        metric(out, "API endpoints", model.apiEndpoints().size());
        metric(out, "Business rules", model.businessRules().size());

        out.append("\n## Technology inventory\n\n");
        out.append("| Layer | Technology | Version | Role |\n|---|---|---|---|\n");
        model.technologies().stream().sorted(Comparator
                .comparing((ProjectKnowledgeModel.Technology t) -> t.layer().name())
                .thenComparing(ProjectKnowledgeModel.Technology::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(t -> nullSafe(t.version())))
                .forEach(t -> out.append("| ").append(cell(t.layer().name())).append(" | ")
                        .append(cell(t.name())).append(" | ").append(cell(t.version())).append(" | ")
                        .append(cell(t.role())).append(" |\n"));

        out.append("\n## Application components\n\n");
        if (model.components().isEmpty()) out.append("_No application components discovered._\n");
        else {
            out.append("| Component | Type | Status | Source |\n|---|---|---|---|\n");
            model.components().stream().sorted(Comparator.comparing(
                    ProjectKnowledgeModel.ApplicationComponent::id, String.CASE_INSENSITIVE_ORDER))
                    .forEach(c -> out.append("| ").append(cell(c.name())).append(" | ")
                            .append(cell(c.type())).append(" | ").append(cell(status(c.status()))).append(" | ")
                            .append(cell(c.sourceLocator())).append(" |\n"));
        }

        out.append("\n## Dependency analysis\n\n");
        out.append("- Graph nodes: ").append(graph.nodeCount()).append("\n");
        out.append("- Internal edges: ").append(graph.internalEdgeCount()).append("\n");
        out.append("- External edges: ").append(graph.externalEdgeCount()).append("\n\n");
        if (graph.cycles().isEmpty()) out.append("No dependency cycles were detected among discovered components.\n");
        else {
            out.append("Detected strongly connected components (review required):\n\n");
            for (var cycle : graph.cycles())
                out.append("- ").append(cycle.componentIds().stream().map(ProjectReportGenerator::code)
                        .collect(Collectors.joining(", "))).append("\n");
        }
        if (!model.dependencies().isEmpty()) {
            out.append("\n| From | To | Kind | Status |\n|---|---|---|---|\n");
            model.dependencies().stream().sorted(Comparator
                    .comparing(ProjectKnowledgeModel.Dependency::from)
                    .thenComparing(ProjectKnowledgeModel.Dependency::to)
                    .thenComparing(d -> nullSafe(d.kind())))
                    .forEach(d -> out.append("| ").append(cell(d.from())).append(" | ")
                            .append(cell(d.to())).append(" | ").append(cell(d.kind())).append(" | ")
                            .append(cell(status(d.status()))).append(" |\n"));
        }

        out.append("\n## Physical data model\n\n");
        if (model.dataStores().isEmpty()) out.append("_No physical data stores discovered._\n");
        for (var store : model.dataStores().stream()
                .sorted(Comparator.comparing(ProjectKnowledgeModel.DataStore::id)).toList()) {
            out.append("### ").append(code(store.id())).append(" (").append(cell(store.vendor()));
            if (store.version() != null && !store.version().isBlank()) out.append(" ").append(cell(store.version()));
            out.append(")\n\n");
            for (var schema : store.schemas().stream()
                    .sorted(Comparator.comparing(ProjectKnowledgeModel.DbSchema::name)).toList()) {
                out.append("#### Schema ").append(code(schema.name())).append("\n\n");
                if (schema.tables().isEmpty()) out.append("_No tables discovered._\n\n");
                for (var table : schema.tables().stream()
                        .sorted(Comparator.comparing(ProjectKnowledgeModel.DbTable::name)).toList()) {
                    out.append("**Table ").append(code(table.name())).append("**\n\n");
                    out.append("| Column | Type | Nullable | Default |\n|---|---|---:|---|\n");
                    for (var column : table.columns())
                        out.append("| ").append(cell(column.name())).append(" | ").append(cell(column.dataType()))
                                .append(" | ").append(column.nullable() ? "Yes" : "No").append(" | ")
                                .append(cell(column.defaultExpression())).append(" |\n");
                    if (!table.constraints().isEmpty()) {
                        out.append("\nConstraints: ");
                        out.append(table.constraints().stream().map(c -> cell(c.type() + " " + c.name()))
                                .collect(Collectors.joining("; "))).append("\n");
                    }
                    if (!table.indexes().isEmpty()) {
                        out.append("Indexes: ");
                        out.append(table.indexes().stream().map(i -> cell(i.name() + " (" + String.join(", ", i.columns()) + ")"))
                                .collect(Collectors.joining("; "))).append("\n");
                    }
                    out.append("\n");
                }
            }
        }

        out.append("## Physical data lineage\n\n");
        if (lineage.edges().isEmpty()) out.append("_No declared physical foreign-key lineage was discovered._\n");
        else {
            out.append("| Source table | Source columns | Target table | Target columns | Constraint | Source | Status |\n");
            out.append("|---|---|---|---|---|---|---|\n");
            for (var edge : lineage.edges())
                out.append("| ").append(cell(edge.sourceTable())).append(" | ")
                        .append(cell(String.join(", ", edge.sourceColumns()))).append(" | ")
                        .append(cell(edge.targetTable())).append(" | ")
                        .append(cell(String.join(", ", edge.targetColumns()))).append(" | ")
                        .append(cell(edge.constraintName())).append(" | ")
                        .append(cell(edge.sourceLocator())).append(" | ")
                        .append(cell(status(edge.status()))).append(" |\n");
        }
        if (!lineage.diagnostics().isEmpty()) {
            out.append("\nLineage diagnostics:\n\n");
            for (String diagnostic : lineage.diagnostics()) out.append("- ").append(cell(diagnostic)).append("\n");
        }
        out.append("\n");

        out.append("## ORM / JPA mappings\n\n");
        if (jpaMappings == null || jpaMappings.entities().isEmpty())
            out.append("_No explicit JPA entity mappings were discovered in this report run._\n");
        else {
            out.append("| Entity | Table | Schema | Source |\n|---|---|---|---|\n");
            for (var entity : jpaMappings.entities())
                out.append("| ").append(cell(entity.qualifiedName())).append(" | ")
                        .append(cell(entity.tableName())).append(" | ").append(cell(entity.schema()))
                        .append(" | ").append(cell(entity.sourceLocator())).append(" |\n");
            if (!jpaMappings.relationships().isEmpty()) {
                out.append("\n| From entity | Field | Relationship | To entity | Join columns |\n|---|---|---|---|---|\n");
                for (var edge : jpaMappings.relationships())
                    out.append("| ").append(cell(edge.fromEntity())).append(" | ")
                            .append(cell(edge.fieldName())).append(" | ").append(cell(edge.kind())).append(" | ")
                            .append(cell(edge.toEntity())).append(" | ")
                            .append(cell(edge.joinColumns().stream().map(join -> join.fieldName() + "→"
                                    + join.columnName() + (join.referencedColumnName().isBlank() ? ""
                                    : "→" + join.referencedColumnName())).collect(Collectors.joining(", "))))
                            .append(" |\n");
            }
        }
        if (jpaMappings != null && !jpaMappings.diagnostics().isEmpty()) {
            out.append("\nJPA mapping diagnostics:\n\n");
            for (String diagnostic : jpaMappings.diagnostics())
                out.append("- ").append(cell(diagnostic)).append("\n");
        }
        out.append("\n");

        out.append("## Logical model\n\n");
        if (model.logicalEntities().isEmpty()) out.append("_No logical entities discovered._\n");
        for (var entity : model.logicalEntities().stream()
                .sorted(Comparator.comparing(ProjectKnowledgeModel.LogicalEntity::name)).toList()) {
            out.append("### ").append(cell(entity.name())).append(" — ").append(status(entity.status())).append("\n\n");
            if (!entity.attributes().isEmpty()) {
                out.append("| Attribute | Business type | Required | Status |\n|---|---|---:|---|\n");
                for (var attribute : entity.attributes())
                    out.append("| ").append(cell(attribute.name())).append(" | ")
                            .append(cell(attribute.businessType())).append(" | ")
                            .append(attribute.required() ? "Yes" : "No").append(" | ")
                            .append(cell(status(attribute.status()))).append(" |\n");
            }
            for (var relationship : entity.relationships())
                out.append("- Relationship to ").append(code(relationship.toEntity())).append(": ")
                        .append(relationship.cardinality()).append(" (").append(status(relationship.status())).append(")\n");
            out.append("\n");
        }

        out.append("## API endpoints\n\n");
        if (model.apiEndpoints().isEmpty()) out.append("_No API endpoints discovered._\n");
        else {
            out.append("| Method | Path | Operation | Status |\n|---|---|---|---|\n");
            model.apiEndpoints().stream().sorted(Comparator
                    .comparing(ProjectKnowledgeModel.ApiEndpoint::path)
                    .thenComparing(ProjectKnowledgeModel.ApiEndpoint::method))
                    .forEach(e -> out.append("| ").append(cell(e.method())).append(" | ")
                            .append(cell(e.path())).append(" | ").append(cell(e.operationId())).append(" | ")
                            .append(cell(status(e.status()))).append(" |\n"));
        }

        out.append("\n## Business rules\n\n");
        if (model.businessRules().isEmpty()) out.append("_No business rules discovered._\n");
        else for (var rule : model.businessRules().stream()
                .sorted(Comparator.comparing(ProjectKnowledgeModel.BusinessRule::id)).toList())
            out.append("- **").append(cell(rule.id())).append("** — ").append(cell(rule.statement()))
                    .append(" (").append(status(rule.status())).append("; source: ")
                    .append(cell(rule.sourceLocator())).append(")\n");

        out.append("\n---\n");
        out.append("**Interpretation note:** This report reflects the supplied UPKM snapshot. ")
                .append("Inferred facts are candidates, not confirmed business truth. ")
                .append("Static discovery does not prove runtime behavior, migration safety, or production readiness.\n");
        return out.toString();
    }

    public String generate(ProjectKnowledgeModel model, JpaMappingAnalyzer.MappingReport jpa,
                           JpaSchemaConsistencyAnalyzer.ConsistencyReport consistency) {
        StringBuilder out = new StringBuilder(generate(model, jpa));
        out.append("\n## JPA schema consistency\n\n");
        if (!consistency.available()) {
            out.append("_Physical schema consistency is unavailable because no physical database schema was supplied._\n");
        } else {
            out.append("| Consistency metric | Count |\n|---|---:|\n");
            metric(out, "Matched entities", consistency.matchedEntities());
            metric(out, "Unmatched entities", consistency.unmatchedEntities());
            metric(out, "Checked explicit columns", consistency.checkedColumns());
            metric(out, "Matched owning relationships", consistency.matchedRelationships());
            metric(out, "Errors", (int) consistency.errorCount());
            metric(out, "Warnings", (int) consistency.warningCount());
            if (!consistency.findings().isEmpty()) {
                out.append("\n| Severity | Code | Entity | Physical table | Finding | Source |\n");
                out.append("|---|---|---|---|---|---|\n");
                for (var finding : consistency.findings())
                    out.append("| ").append(cell(finding.severity())).append(" | ")
                            .append(cell(finding.code())).append(" | ").append(cell(finding.entity())).append(" | ")
                            .append(cell(finding.physicalTable())).append(" | ").append(cell(finding.message()))
                            .append(" | ").append(cell(finding.sourceLocator())).append(" |\n");
            }
        }
        out.append("\n");
        return out.toString();
    }

    private static void row(StringBuilder out, String key, String value) {
        out.append("| ").append(cell(key)).append(" | ").append(cell(value)).append(" |\n");
    }
    private static void metric(StringBuilder out, String name, int value) {
        out.append("| ").append(cell(name)).append(" | ").append(value).append(" |\n");
    }
    private static String cell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r", " ").replace("\n", " ").trim();
    }
    private static String code(String value) {
        if (value == null || value.isBlank()) return "`—`";
        return "`" + value.replace("`", "\\`") + "`";
    }
    private static String status(ProjectKnowledgeModel.FactStatus status) {
        return status == null ? "UNKNOWN" : status.name();
    }
    private static String nullSafe(String value) { return value == null ? "" : value; }
}

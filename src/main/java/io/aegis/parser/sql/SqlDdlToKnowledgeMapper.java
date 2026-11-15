package io.aegis.parser.sql;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts parsed physical DDL into the database-specific portion of the canonical UPKM. */
public final class SqlDdlToKnowledgeMapper {

    public ProjectKnowledgeModel merge(ProjectKnowledgeModel project,
                                       SqlDdlParser.ParseResult parsed,
                                       String dataStoreId,
                                       String schemaName,
                                       String databaseVersion) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(parsed, "parsed");
        require(dataStoreId, "dataStoreId");
        require(schemaName, "schemaName");
        if ("UNKNOWN".equals(parsed.vendor()))
            throw new IllegalArgumentException("A known SQL dialect is required before mapping DDL into UPKM");

        List<ProjectKnowledgeModel.DbTable> tables = parsed.tables().stream()
                .map(this::mapTable)
                .toList();
        ProjectKnowledgeModel.DbSchema schema = new ProjectKnowledgeModel.DbSchema(schemaName, tables);
        ProjectKnowledgeModel.DataStore store = new ProjectKnowledgeModel.DataStore(
                dataStoreId, parsed.vendor(), databaseVersion, List.of(schema));

        List<ProjectKnowledgeModel.DataStore> stores = new ArrayList<>(project.dataStores());
        stores.removeIf(existing -> existing.id().equals(dataStoreId));
        stores.add(store);

        return new ProjectKnowledgeModel(project.project(), project.artifacts(), project.technologies(),
                project.components(), stores, project.logicalEntities(), project.apiEndpoints(),
                project.dependencies(), project.businessRules(), project.analyzedAt(), project.modelVersion());
    }

    private ProjectKnowledgeModel.DbTable mapTable(SqlDdlParser.Table table) {
        List<ProjectKnowledgeModel.DbColumn> columns = table.columns().stream()
                .map(column -> new ProjectKnowledgeModel.DbColumn(column.name(), column.dataType(),
                        column.length(), null, column.scale(), column.nullable(),
                        column.defaultExpression(), column.locator()))
                .toList();

        List<ProjectKnowledgeModel.DbConstraint> constraints = table.constraints().stream()
                .map(constraint -> new ProjectKnowledgeModel.DbConstraint(constraint.name(),
                        mapConstraintType(constraint.type()), constraint.columns(),
                        constraint.referencedTable(), constraint.referencedColumns(), constraint.locator()))
                .toList();

        List<ProjectKnowledgeModel.DbIndex> indexes = table.indexes().stream()
                .map(index -> new ProjectKnowledgeModel.DbIndex(index.name(), index.columns(),
                        index.unique(), index.locator()))
                .toList();

        return new ProjectKnowledgeModel.DbTable(table.name(), columns, constraints, indexes, table.locator());
    }

    private ProjectKnowledgeModel.ConstraintType mapConstraintType(String type) {
        return switch (type) {
            case "PRIMARY_KEY" -> ProjectKnowledgeModel.ConstraintType.PRIMARY_KEY;
            case "FOREIGN_KEY" -> ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY;
            case "UNIQUE" -> ProjectKnowledgeModel.ConstraintType.UNIQUE;
            case "CHECK" -> ProjectKnowledgeModel.ConstraintType.CHECK;
            default -> ProjectKnowledgeModel.ConstraintType.UNKNOWN;
        };
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}

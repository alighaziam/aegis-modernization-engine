package io.aegis.analysis;

import io.aegis.discovery.DatabaseProjectScanner;
import io.aegis.discovery.ProjectSourceScanner;
import io.aegis.discovery.OpenApiProjectScanner;
import io.aegis.domain.ProjectKnowledgeModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Coordinates the current deterministic source, DDL, dependency and physical-lineage analysis stages. */
public final class ProjectAnalysisOrchestrator {
    public AnalysisResult analyze(Path projectRoot, ProjectKnowledgeModel intakeModel,
                                  DatabaseProfile databaseProfile) throws IOException {
        ProjectSourceScanner.ScanReport source = new ProjectSourceScanner().scan(projectRoot, intakeModel);
        ProjectKnowledgeModel model = source.knowledgeModel();
        DatabaseProjectScanner.ScanReport database = null;
        if (databaseProfile != null) {
            database = new DatabaseProjectScanner().scan(projectRoot, model, databaseProfile.dialect(),
                    databaseProfile.dataStoreId(), databaseProfile.schemaName(), databaseProfile.databaseVersion());
            model = database.knowledgeModel();
        }

        OpenApiProjectScanner.ScanReport openApi = new OpenApiProjectScanner().scan(projectRoot, model);
        model = openApi.knowledgeModel();
        SpringApiAnalyzer.Analysis springApi = new SpringApiAnalyzer().analyzeProject(projectRoot);
        List<ProjectKnowledgeModel.ApiEndpoint> apiEndpoints = new ArrayList<>(model.apiEndpoints());
        for (SpringApiAnalyzer.EndpointMapping endpoint : springApi.endpoints()) {
            boolean alreadyKnown = apiEndpoints.stream().anyMatch(existing ->
                    existing.method().equalsIgnoreCase(endpoint.httpMethod())
                            && existing.path().equals(endpoint.path()));
            if (!alreadyKnown) {
                apiEndpoints.add(new ProjectKnowledgeModel.ApiEndpoint(
                        "spring:" + endpoint.httpMethod() + " " + endpoint.path() + ":"
                                + endpoint.controller() + "#" + endpoint.methodName(),
                        endpoint.httpMethod(), endpoint.path(), endpoint.methodName(),
                        endpoint.sourceLocator() + ":" + endpoint.line(),
                        ProjectKnowledgeModel.FactStatus.OBSERVED));
            }
        }
        model = new ProjectKnowledgeModel(model.project(), model.artifacts(), model.technologies(),
                model.components(), model.dataStores(), model.logicalEntities(), apiEndpoints,
                model.dependencies(), model.businessRules(), model.analyzedAt(), model.modelVersion());

        DependencyGraphAnalyzer.GraphReport dependencyGraph = new DependencyGraphAnalyzer().analyze(model);
        DataLineageAnalyzer.LineageReport lineage = new DataLineageAnalyzer().analyze(model);
        JpaMappingAnalyzer.MappingReport jpaMappings = new JpaMappingAnalyzer().analyze(projectRoot);
        JpaSchemaReconciliationAnalyzer.ReconciliationReport jpaReconciliation =
                new JpaSchemaReconciliationAnalyzer().reconcile(model, jpaMappings);
        SqlQueryLineageAnalyzer.LineageReport queryLineage =
                new SqlQueryLineageAnalyzer().analyzeProject(projectRoot, model);
        List<String> diagnostics = new ArrayList<>(source.diagnostics());
        if (database != null) diagnostics.addAll(database.diagnostics());
        diagnostics.addAll(openApi.diagnostics());
        diagnostics.addAll(springApi.diagnostics());
        diagnostics.addAll(lineage.diagnostics());
        diagnostics.addAll(jpaMappings.diagnostics());
        diagnostics.addAll(queryLineage.diagnostics());

        int tables = model.dataStores().stream().flatMap(store -> store.schemas().stream())
                .mapToInt(schema -> schema.tables().size()).sum();
        int columns = model.dataStores().stream().flatMap(store -> store.schemas().stream())
                .flatMap(schema -> schema.tables().stream()).mapToInt(table -> table.columns().size()).sum();
        AnalysisSummary summary = new AnalysisSummary(model.project().id(), model.project().name(),
                source.javaFiles(), source.javaFilesAnalyzed(), source.declaredTypes(), source.imports(),
                dependencyGraph.nodeCount(), dependencyGraph.internalEdgeCount(),
                dependencyGraph.externalEdgeCount(), dependencyGraph.cycles().size(),
                database == null ? 0 : database.filesDiscovered(), openApi.specificationsAnalyzed(),
                openApi.schemasDiscovered(), tables, columns, lineage.edges().size(),
                jpaMappings.entities().size(), jpaMappings.relationships().size(),
                jpaReconciliation.counts().matched(), jpaReconciliation.counts().mismatched(),
                jpaReconciliation.counts().notChecked(),
                queryLineage.queriesAnalyzed(), queryLineage.edges().size(), queryLineage.diagnostics().size(),
                springApi.filesScanned(), springApi.endpoints().size(),
                model.apiEndpoints().size(), model.logicalEntities().size(),
                diagnostics);
        return new AnalysisResult(model, summary, source, database, openApi, dependencyGraph, lineage, jpaMappings, jpaReconciliation, queryLineage, springApi);
    }

    public record DatabaseProfile(String dialect, String dataStoreId, String schemaName, String databaseVersion) {
        public DatabaseProfile {
            if (blank(dialect) || blank(dataStoreId) || blank(schemaName))
                throw new IllegalArgumentException("Database dialect, data store ID and schema name are required");
        }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }

    public record AnalysisSummary(String projectId, String projectName, int javaFiles,
                                  int javaFilesAnalyzed, int declaredTypes, int imports,
                                  int dependencyNodes, int internalDependencyEdges,
                                  int externalDependencyEdges, int dependencyCycles,
                                  int sqlFiles, int openApiSpecifications, int openApiSchemas,
                                  int physicalTables, int physicalColumns,
                                  int foreignKeyLineageEdges, int jpaEntities, int jpaRelationships,
                                  int jpaSchemaMatches, int jpaSchemaMismatches, int jpaSchemaNotChecked,
                                  int sqlQueriesAnalyzed, int queryLineageEdges, int queryLineageDiagnostics,
                                  int springControllerFiles, int springEndpointsDiscovered,
                                  int apiEndpoints, int logicalEntities, List<String> diagnostics) {
        public AnalysisSummary { diagnostics = List.copyOf(diagnostics); }
    }

    public record AnalysisResult(ProjectKnowledgeModel knowledgeModel, AnalysisSummary summary,
                                 ProjectSourceScanner.ScanReport source,
                                 DatabaseProjectScanner.ScanReport database,
                                 OpenApiProjectScanner.ScanReport openApi,
                                 DependencyGraphAnalyzer.GraphReport dependencyGraph,
                                 DataLineageAnalyzer.LineageReport lineage,
                                 JpaMappingAnalyzer.MappingReport jpaMappings,
                                 JpaSchemaReconciliationAnalyzer.ReconciliationReport jpaReconciliation,
                                 SqlQueryLineageAnalyzer.LineageReport queryLineage,
                                 SpringApiAnalyzer.Analysis springApi) {}
}

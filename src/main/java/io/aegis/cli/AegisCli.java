package io.aegis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.analysis.DependencyGraphAnalyzer;
import io.aegis.analysis.ProjectAnalysisOrchestrator;
import io.aegis.analysis.DataLineageAnalyzer;
import io.aegis.analysis.JpaMappingAnalyzer;
import io.aegis.analysis.JpaSchemaReconciliationAnalyzer;
import io.aegis.analysis.SqlQueryLineageAnalyzer;
import io.aegis.analysis.SpringApiAnalyzer;
import io.aegis.analysis.JavaBehaviorAnalyzer;
import io.aegis.analysis.LogicalModelInferer;
import io.aegis.discovery.TechnologyDetector;
import io.aegis.discovery.ProjectSourceScanner;
import io.aegis.discovery.DatabaseProjectScanner;
import io.aegis.reporting.ProjectReportGenerator;
import io.aegis.reporting.MigrationPlanReportGenerator;
import io.aegis.parser.openapi.OpenApiYamlAnalyzer;
import io.aegis.planning.ModernizationPlanner;
import io.aegis.intake.IntakeService;
import io.aegis.intake.IntakeToKnowledgeModelMapper;
import io.aegis.parser.sql.SqlDdlParser;
import io.aegis.parser.sql.DatabaseDialectRegistry;
import io.aegis.parser.sql.SqlDdlToKnowledgeMapper;
import io.aegis.parser.java.JavaSourceAnalyzer;
import io.aegis.parser.java.JavaSourceToKnowledgeMapper;
import io.aegis.transformation.JavaImportMigrationEngine;
import io.aegis.transformation.OraclePostgresDdlPlanner;
import io.aegis.transformation.DatabaseMigrationPlanner;
import io.aegis.persistence.oracle.OracleArtifactRepository;
import io.aegis.persistence.oracle.OracleConnectionConfig;
import io.aegis.persistence.oracle.OracleProjectRepository;
import io.aegis.persistence.oracle.OracleKnowledgeJournalRepository;
import io.aegis.evidence.EvidenceRecord;
import io.aegis.evidence.ConflictRegistry;
import io.aegis.persistence.oracle.OracleSchemaVerifier;
import io.aegis.persistence.oracle.OracleIntakeRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.StandardCopyOption;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-line entry point for deterministic, offline Aegis operations. */
public final class AegisCli {
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    private AegisCli() {}

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        if (args == null || args.length == 0 || isHelp(args[0])) {
            usage();
            return args == null || args.length == 0 ? 2 : 0;
        }

        try {
            return switch (args[0]) {
                case "intake" -> runIntake(args);
                case "parse-ddl" -> runParseDdl(args);
                case "list-db-dialects" -> runListDbDialects(args);
                case "map-ddl" -> runMapDdl(args);
                case "analyze-java" -> runAnalyzeJava(args);
                case "analyze-behavior" -> runAnalyzeBehavior(args);
                case "detect-tech" -> runDetectTech(args);
                case "analyze-project" -> runAnalyzeProject(args);
                case "analyze-workspace" -> runAnalyzeWorkspace(args);
                case "analyze-database" -> runAnalyzeDatabase(args);
                case "analyze-lineage" -> runAnalyzeLineage(args);
                case "analyze-jpa" -> runAnalyzeJpa(args);
                case "reconcile-jpa" -> runReconcileJpa(args);
                case "analyze-query-lineage" -> runAnalyzeQueryLineage(args);
                case "analyze-spring-api" -> runAnalyzeSpringApi(args);
                case "render-report" -> runRenderReport(args);
                case "analyze-openapi" -> runAnalyzeOpenApi(args);
                case "infer-model" -> runInferModel(args);
                case "plan" -> runPlan(args);
                case "plan-workspace" -> runPlanWorkspace(args);
                case "migrate-java-imports" -> runMigrateJavaImports(args);
                case "plan-ddl-migration" -> runPlanDdlMigration(args);
                case "db-check" -> runDbCheck(args);
                case "store-intake" -> runStoreIntake(args);
                case "store-artifact" -> runStoreArtifact(args);
                case "load-model" -> runLoadModel(args);
                case "store-evidence" -> runStoreEvidence(args);
                case "list-evidence" -> runListEvidence(args);
                case "store-conflict-event" -> runStoreConflictEvent(args);
                case "list-conflicts" -> runListConflicts(args);
                default -> {
                    System.err.println("Unknown command: " + args[0]);
                    usage();
                    yield 2;
                }
            };
        } catch (Exception e) {
            System.err.println("Aegis error: " + e.getMessage());
            return 1;
        }
    }

    private static int runListDbDialects(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: aegis-modernization-engine list-db-dialects");
            return 2;
        }
        System.out.println(JSON.writeValueAsString(new DatabaseDialectRegistry().all()));
        return 0;
    }

    private static int runDbCheck(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: aegis-modernization-engine db-check");
            return 2;
        }
        try (HikariDataSource dataSource = OracleConnectionConfig.fromEnvironment().createDataSource()) {
            new OracleProjectRepository(dataSource).verifyConnection();
            var schema = new OracleSchemaVerifier().inspect(dataSource);
            System.out.println(JSON.writeValueAsString(new DbCheckOutput("ORACLE",
                    schema.complete() ? "READY" : "SCHEMA_INCOMPLETE", schema.presentTables(), schema.missingTables())));
            return schema.complete() ? 0 : 3;
        }
    }

    private static int runStoreIntake(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: aegis-modernization-engine store-intake <intake.json>");
            return 2;
        }
        Path intakeFile = Path.of(args[1]).toAbsolutePath().normalize();
        IntakeService.IntakeRequest request = IntakeService.readRequest(intakeFile);
        ProjectKnowledgeModel model = new IntakeToKnowledgeModelMapper().map(request);
        OracleIntakeRepository.IntakeSaveResult saved;
        try (HikariDataSource dataSource = OracleConnectionConfig.fromEnvironment().createDataSource()) {
            saved = new OracleIntakeRepository(dataSource).save(request, model, intakeFile);
        }
        System.out.println(JSON.writeValueAsString(new StoredIntakeOutput(
                saved.projectId(), model.project().name(), saved.persistedArtifacts(),
                saved.pendingArtifacts(), "ORACLE")));
        return saved.pendingArtifacts() == 0 ? 0 : 3;
    }

    private static int runStoreArtifact(String[] args) throws Exception {
        if (args.length != 7) {
            System.err.println("Usage: aegis-modernization-engine store-artifact <project-id> <artifact-id> <category> <format> <origin> <file>");
            return 2;
        }
        OracleArtifactRepository.StoredArtifact artifact;
        try (HikariDataSource dataSource = OracleConnectionConfig.fromEnvironment().createDataSource()) {
            artifact = new OracleArtifactRepository(dataSource).saveFile(
                    args[1], args[2], args[3], args[4], args[5], Path.of(args[6]));
        }
        System.out.println(JSON.writeValueAsString(new StoredArtifactOutput(
                artifact.projectId(), artifact.artifactId(), artifact.sha256(), artifact.sizeBytes(), "ORACLE")));
        return 0;
    }

    private static int runLoadModel(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: aegis-modernization-engine load-model <project-id>");
            return 2;
        }
        try (HikariDataSource dataSource = OracleConnectionConfig.fromEnvironment().createDataSource()) {
            var model = new OracleProjectRepository(dataSource).findLatestSnapshot(args[1]);
            if (model.isEmpty()) {
                System.err.println("No stored analysis snapshot found for project: " + args[1]);
                return 3;
            }
            System.out.println(JSON.writeValueAsString(model.get()));
            return 0;
        }
    }

    private static int runStoreEvidence(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine store-evidence <project-id> <evidence.json>");
            return 2;
        }
        EvidenceRecord evidence = JSON.readValue(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8), EvidenceRecord.class);
        try (HikariDataSource dataSource = OracleConnectionConfig.fromEnvironment().createDataSource()) {
            new OracleKnowledgeJournalRepository(dataSource).append(args[1], evidence);
        }
        System.out.println(JSON.writeValueAsString(new JournalWriteOutput(args[1], evidence.evidenceId(), "EVIDENCE", "ORACLE")));
        return 0;
    }

    private static int runListEvidence(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine list-evidence <project-id> <analysis-run-id>");
            return 2;
        }
        try (HikariDataSource dataSource = OracleConnectionConfig.fromEnvironment().createDataSource()) {
            var records = new OracleKnowledgeJournalRepository(dataSource).findByAnalysisRun(args[1], args[2]);
            System.out.println(JSON.writeValueAsString(records));
        }
        return 0;
    }

    private static int runStoreConflictEvent(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine store-conflict-event <project-id> <event.json>");
            return 2;
        }
        ConflictRegistry.ConflictEvent event = JSON.readValue(
                Files.readString(Path.of(args[2]), StandardCharsets.UTF_8), ConflictRegistry.ConflictEvent.class);
        try (HikariDataSource dataSource = OracleConnectionConfig.fromEnvironment().createDataSource()) {
            new OracleKnowledgeJournalRepository(dataSource).append(args[1], event);
        }
        System.out.println(JSON.writeValueAsString(new JournalWriteOutput(args[1], event.eventId(), "CONFLICT_EVENT", "ORACLE")));
        return 0;
    }

    private static int runListConflicts(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: aegis-modernization-engine list-conflicts <project-id>");
            return 2;
        }
        try (HikariDataSource dataSource = OracleConnectionConfig.fromEnvironment().createDataSource()) {
            var events = new OracleKnowledgeJournalRepository(dataSource).history(args[1]);
            System.out.println(JSON.writeValueAsString(events));
        }
        return 0;
    }

    private static int runIntake(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine intake <intake.json> <artifact-store-dir>");
            return 2;
        }
        IntakeService.IntakeReport report = new IntakeService(Path.of(args[2])).ingest(Path.of(args[1]));
        System.out.println(JSON.writeValueAsString(report));
        return report.warnings().isEmpty() ? 0 : 3;
    }

    private static int runParseDdl(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Usage: aegis-modernization-engine parse-ddl <schema.sql> <oracle|postgresql|mysql|mariadb|sqlserver|db2> [source-name]");
            return 2;
        }
        Path input = Path.of(args[1]);
        String sql = Files.readString(input, StandardCharsets.UTF_8);
        String sourceName = args.length == 4 ? args[3] : input.getFileName().toString();
        SqlDdlParser.ParseResult result = new SqlDdlParser().parse(sql, sourceName, args[2]);
        System.out.println(JSON.writeValueAsString(result));
        return result.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runMapDdl(String[] args) throws Exception {
        if (args.length < 6 || args.length > 7) {
            System.err.println("Usage: aegis-modernization-engine map-ddl <intake.json> <schema.sql> <oracle|postgresql|mysql|mariadb|sqlserver|db2> <store-id> <schema-name> [db-version]");
            return 2;
        }
        IntakeService.IntakeRequest request = IntakeService.readRequest(Path.of(args[1]));
        ProjectKnowledgeModel project = new IntakeToKnowledgeModelMapper().map(request);
        Path sqlFile = Path.of(args[2]);
        String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
        SqlDdlParser.ParseResult parsed = new SqlDdlParser().parse(sql, sqlFile.getFileName().toString(), args[3]);
        ProjectKnowledgeModel mapped = new SqlDdlToKnowledgeMapper()
                .merge(project, parsed, args[4], args[5], args.length == 7 ? args[6] : null);
        System.out.println(JSON.writeValueAsString(new DdlAnalysisOutput(mapped, parsed)));
        return parsed.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runAnalyzeJava(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine analyze-java <source.java> <intake.json>");
            return 2;
        }
        IntakeService.IntakeRequest request = IntakeService.readRequest(Path.of(args[2]));
        ProjectKnowledgeModel project = new IntakeToKnowledgeModelMapper().map(request);
        Path sourceFile = Path.of(args[1]);
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        JavaSourceAnalyzer.AnalysisResult analysis =
                new JavaSourceAnalyzer().analyze(source, sourceFile.toString());
        ProjectKnowledgeModel mapped = new JavaSourceToKnowledgeMapper().merge(project, java.util.List.of(analysis));
        DependencyGraphAnalyzer.GraphReport graph = new DependencyGraphAnalyzer().analyze(mapped);
        System.out.println(JSON.writeValueAsString(new JavaAnalysisOutput(mapped, analysis, graph)));
        return analysis.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runAnalyzeLineage(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: aegis-modernization-engine analyze-lineage <upkm.json>");
            return 2;
        }
        ProjectKnowledgeModel model = JSON.readValue(
                Files.readString(Path.of(args[1]), StandardCharsets.UTF_8), ProjectKnowledgeModel.class);
        DataLineageAnalyzer.LineageReport report = new DataLineageAnalyzer().analyze(model);
        System.out.println(JSON.writeValueAsString(report));
        return report.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runAnalyzeJpa(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: aegis-modernization-engine analyze-jpa <java-source-root>");
            return 2;
        }
        JpaMappingAnalyzer.MappingReport report = new JpaMappingAnalyzer().analyze(Path.of(args[1]));
        System.out.println(JSON.writeValueAsString(report));
        return report.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runReconcileJpa(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine reconcile-jpa <upkm.json> <orm-mappings.json>");
            return 2;
        }
        ProjectKnowledgeModel model = JSON.readValue(
                Files.readString(Path.of(args[1]), StandardCharsets.UTF_8), ProjectKnowledgeModel.class);
        JpaMappingAnalyzer.MappingReport mappings = JSON.readValue(
                Files.readString(Path.of(args[2]), StandardCharsets.UTF_8), JpaMappingAnalyzer.MappingReport.class);
        JpaSchemaReconciliationAnalyzer.ReconciliationReport report =
                new JpaSchemaReconciliationAnalyzer().reconcile(model, mappings);
        System.out.println(JSON.writeValueAsString(report));
        return report.counts().mismatched() == 0 && report.counts().ambiguous() == 0 ? 0 : 3;
    }

    private static int runAnalyzeSpringApi(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: aegis-modernization-engine analyze-spring-api <project-root>");
            return 2;
        }
        SpringApiAnalyzer.Analysis report = new SpringApiAnalyzer().analyzeProject(Path.of(args[1]));
        System.out.println(JSON.writeValueAsString(report));
        return report.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runAnalyzeQueryLineage(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine analyze-query-lineage <project-root> <upkm.json>");
            return 2;
        }
        ProjectKnowledgeModel model = JSON.readValue(
                Files.readString(Path.of(args[2]), StandardCharsets.UTF_8), ProjectKnowledgeModel.class);
        SqlQueryLineageAnalyzer.LineageReport report =
                new SqlQueryLineageAnalyzer().analyzeProject(Path.of(args[1]), model);
        System.out.println(JSON.writeValueAsString(report));
        return report.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runRenderReport(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine render-report <upkm.json> <report.md>");
            return 2;
        }
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (input.equals(output)) {
            System.err.println("Report output must not overwrite the UPKM input.");
            return 2;
        }
        ProjectKnowledgeModel model = JSON.readValue(Files.readString(input, StandardCharsets.UTF_8),
                ProjectKnowledgeModel.class);
        String markdown = new ProjectReportGenerator().generate(model);
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".aegis-report-", ".tmp");
        try {
            Files.writeString(temp, markdown, StandardCharsets.UTF_8);
            try {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        System.out.println(JSON.writeValueAsString(new ReportOutput(input.toString(), output.toString(),
                markdown.length(), "MARKDOWN")));
        return 0;
    }

    private static int runAnalyzeDatabase(String[] args) throws Exception {
        if (args.length != 6 && args.length != 7) {
            System.err.println("Usage: aegis-modernization-engine analyze-database <project-root> <intake.json> <dialect> <store-id> <schema-name> [db-version]");
            return 2;
        }
        IntakeService.IntakeRequest request = IntakeService.readRequest(Path.of(args[2]));
        ProjectKnowledgeModel project = new IntakeToKnowledgeModelMapper().map(request);
        DatabaseProjectScanner.ScanReport report = new DatabaseProjectScanner().scan(
                Path.of(args[1]), project, args[3], args[4], args[5], args.length == 7 ? args[6] : null);
        System.out.println(JSON.writeValueAsString(report));
        return report.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runAnalyzeWorkspace(String[] args) throws Exception {
        if (args.length != 4 && args.length != 7 && args.length != 8) {
            System.err.println("Usage: aegis-modernization-engine analyze-workspace <project-root> <intake.json> <output-dir> [dialect store-id schema-name [db-version]]");
            return 2;
        }
        Path projectRoot = Path.of(args[1]).toRealPath();
        IntakeService.IntakeRequest request = IntakeService.readRequest(Path.of(args[2]));
        ProjectKnowledgeModel base = new IntakeToKnowledgeModelMapper().map(request);
        ProjectAnalysisOrchestrator.DatabaseProfile database = args.length >= 7
                ? new ProjectAnalysisOrchestrator.DatabaseProfile(args[4], args[5], args[6],
                        args.length == 8 ? args[7] : null)
                : null;
        var result = new ProjectAnalysisOrchestrator().analyze(projectRoot, base, database);

        Path outputDir = Path.of(args[3]).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);
        Path realOutputDir = outputDir.toRealPath();
        if (realOutputDir.startsWith(projectRoot) || projectRoot.startsWith(realOutputDir))
            throw new IllegalArgumentException("Output directory must be separate from the source project tree");
        writeWorkspaceFile(realOutputDir.resolve("upkm.json"), JSON.writeValueAsString(result.knowledgeModel()));
        writeWorkspaceFile(realOutputDir.resolve("architecture.md"), new ProjectReportGenerator().generate(result.knowledgeModel(), result.jpaMappings(), result.jpaReconciliation(), result.queryLineage(), result.springApi(), result.apiConsistency()));
        writeWorkspaceFile(realOutputDir.resolve("analysis-summary.json"), JSON.writeValueAsString(result.summary()));
        writeWorkspaceFile(realOutputDir.resolve("openapi-inventory.json"), JSON.writeValueAsString(
                new OpenApiInventory(result.openApi().specifications(), result.openApi().schemas(), result.openApi().diagnostics())));
        writeWorkspaceFile(realOutputDir.resolve("orm-mappings.json"), JSON.writeValueAsString(result.jpaMappings()));
        writeWorkspaceFile(realOutputDir.resolve("jpa-schema-reconciliation.json"),
                JSON.writeValueAsString(result.jpaReconciliation()));
        writeWorkspaceFile(realOutputDir.resolve("query-lineage.json"), JSON.writeValueAsString(result.queryLineage()));
        writeWorkspaceFile(realOutputDir.resolve("spring-api-mappings.json"), JSON.writeValueAsString(result.springApi()));
        writeWorkspaceFile(realOutputDir.resolve("api-contract-consistency.json"), JSON.writeValueAsString(result.apiConsistency()));
        System.out.println(JSON.writeValueAsString(result.summary()));
        return result.summary().diagnostics().isEmpty() ? 0 : 3;
    }

    private static void writeWorkspaceFile(Path output, String content) throws Exception {
        Path temp = Files.createTempFile(output.getParent(), ".aegis-workspace-", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static int runAnalyzeProject(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine analyze-project <project-root> <intake.json>");
            return 2;
        }
        IntakeService.IntakeRequest request = IntakeService.readRequest(Path.of(args[2]));
        ProjectKnowledgeModel project = new IntakeToKnowledgeModelMapper().map(request);
        ProjectSourceScanner.ScanReport report = new ProjectSourceScanner().scan(Path.of(args[1]), project);
        System.out.println(JSON.writeValueAsString(report));
        return report.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runAnalyzeBehavior(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: aegis-modernization-engine analyze-behavior <source.java>");
            return 2;
        }
        Path source = Path.of(args[1]);
        JavaBehaviorAnalyzer.BehaviorReport report = new JavaBehaviorAnalyzer().analyze(
                Files.readString(source, StandardCharsets.UTF_8), source.toString());
        System.out.println(JSON.writeValueAsString(report));
        return report.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runDetectTech(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: aegis-modernization-engine detect-tech <pom.xml|build.gradle|source.java>...");
            return 2;
        }
        java.util.Map<String, String> inputs = new java.util.LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            Path path = Path.of(args[i]);
            inputs.put(path.toString(), Files.readString(path, StandardCharsets.UTF_8));
        }
        TechnologyDetector.DetectionReport report = new TechnologyDetector().detect(inputs);
        System.out.println(JSON.writeValueAsString(report));
        return report.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runAnalyzeOpenApi(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: aegis-modernization-engine analyze-openapi <openapi.yaml|json> <intake.json>");
            return 2;
        }
        Path spec = Path.of(args[1]);
        IntakeService.IntakeRequest request = IntakeService.readRequest(Path.of(args[2]));
        ProjectKnowledgeModel project = new IntakeToKnowledgeModelMapper().map(request);
        OpenApiYamlAnalyzer analyzer = new OpenApiYamlAnalyzer();
        OpenApiYamlAnalyzer.Analysis analysis = analyzer.analyze(
                Files.readString(spec, StandardCharsets.UTF_8), spec.toString());
        ProjectKnowledgeModel mapped = analyzer.merge(project, analysis);
        System.out.println(JSON.writeValueAsString(new OpenApiAnalysisOutput(mapped, analysis)));
        return analysis.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runInferModel(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: aegis-modernization-engine infer-model <intake.json> [source.java...]");
            return 2;
        }
        IntakeService.IntakeRequest request = IntakeService.readRequest(Path.of(args[1]));
        ProjectKnowledgeModel project = new IntakeToKnowledgeModelMapper().map(request);
        java.util.List<String> sources = new java.util.ArrayList<>();
        for (int i = 2; i < args.length; i++)
            sources.add(Files.readString(Path.of(args[i]), StandardCharsets.UTF_8));
        LogicalModelInferer.InferenceReport report = new LogicalModelInferer().infer(project, sources);
        System.out.println(JSON.writeValueAsString(report));
        return 0;
    }

    private static int runPlanDdlMigration(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) {
            System.err.println("Usage: aegis-modernization-engine plan-ddl-migration <schema.sql> <source-dialect> <target-dialect> <target-schema>");
            return 2;
        }
        Path input = Path.of(args[1]);
        String sourceDialect = args.length == 4 ? args[2] : args[2];
        String targetDialect = args.length == 4 ? "postgresql" : args[3];
        String targetSchema = args.length == 4 ? args[3] : args[4];
        String sql = Files.readString(input, StandardCharsets.UTF_8);
        SqlDdlParser.ParseResult parsed = new SqlDdlParser().parse(sql, input.getFileName().toString(), sourceDialect);
        DatabaseMigrationPlanner.MigrationPlan plan =
                new DatabaseMigrationPlanner().plan(parsed, targetDialect, targetSchema);
        System.out.println(JSON.writeValueAsString(new DdlMigrationOutput(parsed, plan)));
        return plan.diagnostics().isEmpty() ? 0 : 3;
    }

    private static int runMigrateJavaImports(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !"--write".equals(args[2]))) {
            System.err.println("Usage: aegis-modernization-engine migrate-java-imports <source.java> [--write]");
            return 2;
        }
        Path sourceFile = Path.of(args[1]).toAbsolutePath().normalize();
        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        JavaImportMigrationEngine.MigrationResult result =
                new JavaImportMigrationEngine().migrate(source, sourceFile.getFileName().toString());
        boolean written = false;
        if (args.length == 3 && result.safeToReview() && !result.replacements().isEmpty()) {
            Path backup = sourceFile.resolveSibling(sourceFile.getFileName() + ".aegis.bak");
            if (Files.exists(backup)) throw new IllegalStateException("Backup already exists; refusing to overwrite: " + backup);
            Files.copy(sourceFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
            Path temp = Files.createTempFile(sourceFile.getParent(), ".aegis-", ".tmp");
            try {
                Files.writeString(temp, result.transformedSource(), StandardCharsets.UTF_8);
                Files.move(temp, sourceFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            written = true;
        }
        System.out.println(JSON.writeValueAsString(new JavaImportMigrationOutput(result, written)));
        return result.safeToReview() ? 0 : 3;
    }

    private static int runPlanWorkspace(String[] args) throws Exception {
        if (args.length != 9) {
            System.err.println("Usage: aegis-modernization-engine plan-workspace <upkm.json> <java-version> <framework> <framework-version> <database> <frontend|-> <deployment|-> <output-dir>");
            return 2;
        }
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        ProjectKnowledgeModel project = JSON.readValue(Files.readString(input, StandardCharsets.UTF_8),
                ProjectKnowledgeModel.class);
        ModernizationPlanner.TargetProfile target = new ModernizationPlanner.TargetProfile(
                Integer.parseInt(args[2]), args[3], args[4], args[5],
                optionalProfileValue(args[6]), optionalProfileValue(args[7]));
        ModernizationPlanner.Plan plan = new ModernizationPlanner().create(project, target);
        Path outputDir = Path.of(args[8]).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);
        Path jsonOutput = outputDir.resolve("migration-plan.json");
        Path markdownOutput = outputDir.resolve("migration-plan.md");
        if (input.equals(jsonOutput) || input.equals(markdownOutput))
            throw new IllegalArgumentException("Migration plan output must not overwrite the UPKM input");
        writeWorkspaceFile(jsonOutput, JSON.writeValueAsString(plan));
        writeWorkspaceFile(markdownOutput, new MigrationPlanReportGenerator().generate(plan));
        System.out.println(JSON.writeValueAsString(new PlanWorkspaceOutput(
                project.project().id(), jsonOutput.toString(), markdownOutput.toString(), plan.steps().size())));
        return 0;
    }

    private static String optionalProfileValue(String value) {
        return value == null || value.isBlank() || value.equals("-") ? null : value;
    }

    private static int runPlan(String[] args) throws Exception {
        if (args.length != 8) {
            System.err.println("Usage: aegis-modernization-engine plan <intake.json> <java-version> <framework> <framework-version> <database> <frontend> <deployment>");
            return 2;
        }
        IntakeService.IntakeRequest request = IntakeService.readRequest(Path.of(args[1]));
        ProjectKnowledgeModel project = new IntakeToKnowledgeModelMapper().map(request);
        ModernizationPlanner.TargetProfile target = new ModernizationPlanner.TargetProfile(
                Integer.parseInt(args[2]), args[3], args[4], args[5], args[6], args[7]);
        ModernizationPlanner.Plan plan = new ModernizationPlanner().create(project, target);
        System.out.println(JSON.writeValueAsString(plan));
        return 0;
    }

    public record OpenApiInventory(java.util.List<io.aegis.discovery.OpenApiProjectScanner.SpecificationSummary> specifications,                                    java.util.List<io.aegis.parser.openapi.OpenApiYamlAnalyzer.Schema> schemas,                                    java.util.List<String> diagnostics) {}     public record PlanWorkspaceOutput(String projectId, String jsonOutput, String markdownOutput, int phases) {}
    public record ReportOutput(String input, String output, int characters, String format) {}
    public record OpenApiAnalysisOutput(ProjectKnowledgeModel knowledgeModel, OpenApiYamlAnalyzer.Analysis openApi) {}
    public record JavaImportMigrationOutput(JavaImportMigrationEngine.MigrationResult migration, boolean written) {}
    public record DdlMigrationOutput(SqlDdlParser.ParseResult sourceAnalysis,
                                     DatabaseMigrationPlanner.MigrationPlan migrationPlan) {}
    public record StoredArtifactOutput(String projectId, String artifactId, String sha256, long sizeBytes, String persistenceDatabase) {}
    public record JournalWriteOutput(String projectId, String recordId, String recordType, String persistenceDatabase) {}
    public record DbCheckOutput(String database, String status, java.util.List<String> presentTables,
                                java.util.List<String> missingTables) {}
    public record StoredProjectOutput(String projectId, String projectName,
                                      java.time.Instant analyzedAt, String persistenceDatabase) {}
    public record StoredIntakeOutput(String projectId, String projectName, int persistedArtifacts,
                                     int pendingArtifacts, String persistenceDatabase) {}

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equals(arg);
    }

    public record DdlAnalysisOutput(ProjectKnowledgeModel knowledgeModel, SqlDdlParser.ParseResult ddl) {}
    public record JavaAnalysisOutput(ProjectKnowledgeModel knowledgeModel, JavaSourceAnalyzer.AnalysisResult javaAnalysis, DependencyGraphAnalyzer.GraphReport dependencyGraph) {}

    private static void usage() {
        System.out.println("""
                Aegis Modernization Engine (offline, deterministic)

                Commands:
                  list-db-dialects
                      List recognized database dialects and their implemented capabilities.
                  intake <intake.json> <artifact-store-dir>
                      Validate an intake envelope and register supplied artifacts.
                  parse-ddl <schema.sql> <oracle|postgresql|mysql|mariadb|sqlserver|db2> [source-name]
                      Parse the supported SQL DDL subset and emit JSON.
                  map-ddl <intake.json> <schema.sql> <oracle|postgresql|mysql|mariadb|sqlserver|db2> <store-id> <schema-name> [db-version]
                      Build a project UPKM and merge the parsed physical database schema.
                  analyze-java <source.java> <intake.json>
                      Extract Java declarations and imports into the project UPKM.
                  analyze-project <project-root> <intake.json>
                      Scan a complete Java/Maven/Gradle project tree without executing its build.
                  analyze-workspace <project-root> <intake.json> <output-dir> [dialect store-id schema-name [db-version]]
                      Run the end-to-end deterministic source, optional DDL, graph and lineage pipeline.
                  analyze-database <project-root> <intake.json> <dialect> <store-id> <schema-name> [db-version]
                      Scan SQL DDL files across a project tree and merge the supported physical schema.
                  analyze-lineage <upkm.json>
                      Extract declared physical foreign-key lineage and report incomplete mappings.
                  render-report <upkm.json> <report.md>
                      Render a deterministic Markdown architecture and modernization report.
                  detect-tech <pom.xml|build.gradle|source.java>...
                      Detect supported Java, Spring and library markers and versions.
                  analyze-openapi <openapi.yaml|json> <intake.json>
                      Parse OpenAPI endpoints and schemas and merge endpoints into UPKM.
                  infer-model <intake.json> [source.java...]
                      Infer logical entities and candidate business rules; review is required.
                  plan <intake.json> <java-version> <framework> <framework-version> <database> <frontend> <deployment>
                  plan-workspace <upkm.json> <java-version> <framework> <framework-version> <database> <frontend|-> <deployment|-> <output-dir>
                      Generate a migration plan from the discovered workspace UPKM.
                      Generate an explainable target architecture and migration sequence.
                  plan-ddl-migration <schema.sql> <source-dialect> <target-dialect> <target-schema>
                      Plan DDL conversion only when a tested source/target adapter exists.
                  db-check
                      Check Oracle connectivity and required schema tables.
                  store-intake <intake.json>
                      Atomically store the project snapshot and supplied artifacts in Oracle.
                  store-artifact <project-id> <artifact-id> <category> <format> <origin> <file>
                      Store one immutable artifact in Oracle.
                  load-model <project-id>
                      Load the latest stored project snapshot from Oracle.
                  store-evidence <project-id> <evidence.json>
                      Append a provenance evidence record to Oracle.
                  list-evidence <project-id> <analysis-run-id>
                      Read provenance evidence for an analysis run.
                  store-conflict-event <project-id> <event.json>
                      Append a conflict lifecycle event to Oracle.
                  list-conflicts <project-id>
                      Read append-only conflict history from Oracle.

                Exit codes:
                  0  completed without review warnings
                  1  execution or validation error
                  2  invalid command usage
                  3  completed with diagnostics or review warnings
                """);
    }
}

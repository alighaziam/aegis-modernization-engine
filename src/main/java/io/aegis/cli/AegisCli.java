package io.aegis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.analysis.DependencyGraphAnalyzer;
import io.aegis.analysis.JavaBehaviorAnalyzer;
import io.aegis.analysis.LogicalModelInferer;
import io.aegis.discovery.TechnologyDetector;
import io.aegis.parser.openapi.OpenApiYamlAnalyzer;
import io.aegis.planning.ModernizationPlanner;
import io.aegis.intake.IntakeService;
import io.aegis.intake.IntakeToKnowledgeModelMapper;
import io.aegis.parser.sql.SqlDdlParser;
import io.aegis.parser.sql.SqlDdlToKnowledgeMapper;
import io.aegis.parser.java.JavaSourceAnalyzer;
import io.aegis.parser.java.JavaSourceToKnowledgeMapper;
import io.aegis.transformation.JavaImportMigrationEngine;
import io.aegis.transformation.OraclePostgresDdlPlanner;
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
                case "map-ddl" -> runMapDdl(args);
                case "analyze-java" -> runAnalyzeJava(args);
                case "analyze-behavior" -> runAnalyzeBehavior(args);
                case "detect-tech" -> runDetectTech(args);
                case "analyze-openapi" -> runAnalyzeOpenApi(args);
                case "infer-model" -> runInferModel(args);
                case "plan" -> runPlan(args);
                case "migrate-java-imports" -> runMigrateJavaImports(args);
                case "plan-ddl-migration" -> runPlanDdlMigration(args);
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
            System.err.println("Usage: aegis-modernization-engine parse-ddl <schema.sql> <oracle|postgresql> [source-name]");
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
            System.err.println("Usage: aegis-modernization-engine map-ddl <intake.json> <schema.sql> <oracle|postgresql> <store-id> <schema-name> [db-version]");
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
        if (args.length != 4 || !"oracle".equalsIgnoreCase(args[2])) {
            System.err.println("Usage: aegis-modernization-engine plan-ddl-migration <schema.sql> oracle <target-schema>");
            return 2;
        }
        Path input = Path.of(args[1]);
        String sql = Files.readString(input, StandardCharsets.UTF_8);
        SqlDdlParser.ParseResult parsed = new SqlDdlParser().parse(sql, input.getFileName().toString(), "oracle");
        OraclePostgresDdlPlanner.MigrationPlan plan =
                new OraclePostgresDdlPlanner().plan(parsed, args[3]);
        System.out.println(JSON.writeValueAsString(new DdlMigrationOutput(parsed, plan)));
        return plan.diagnostics().stream().anyMatch(d -> "ERROR".equals(d.status())) ? 3 : 0;
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

    public record OpenApiAnalysisOutput(ProjectKnowledgeModel knowledgeModel, OpenApiYamlAnalyzer.Analysis openApi) {}
    public record JavaImportMigrationOutput(JavaImportMigrationEngine.MigrationResult migration, boolean written) {}
    public record DdlMigrationOutput(SqlDdlParser.ParseResult sourceAnalysis,
                                     OraclePostgresDdlPlanner.MigrationPlan migrationPlan) {}

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equals(arg);
    }

    public record DdlAnalysisOutput(ProjectKnowledgeModel knowledgeModel, SqlDdlParser.ParseResult ddl) {}
    public record JavaAnalysisOutput(ProjectKnowledgeModel knowledgeModel, JavaSourceAnalyzer.AnalysisResult javaAnalysis, DependencyGraphAnalyzer.GraphReport dependencyGraph) {}

    private static void usage() {
        System.out.println("""
                Aegis Modernization Engine (offline, deterministic)

                Commands:
                  intake <intake.json> <artifact-store-dir>
                      Validate an intake envelope and register supplied artifacts.
                  parse-ddl <schema.sql> <oracle|postgresql> [source-name]
                      Parse the supported SQL DDL subset and emit JSON.
                  map-ddl <intake.json> <schema.sql> <oracle|postgresql> <store-id> <schema-name> [db-version]
                      Build a project UPKM and merge the parsed physical database schema.
                  analyze-java <source.java> <intake.json>
                      Extract Java declarations and imports into the project UPKM.
                  detect-tech <pom.xml|build.gradle|source.java>...
                      Detect supported Java, Spring and library markers and versions.
                  analyze-openapi <openapi.yaml|json> <intake.json>
                      Parse OpenAPI endpoints and schemas and merge endpoints into UPKM.
                  infer-model <intake.json> [source.java...]
                      Infer logical entities and candidate business rules; review is required.
                  plan <intake.json> <java-version> <framework> <framework-version> <database> <frontend> <deployment>
                      Generate an explainable target architecture and migration sequence.

                Exit codes:
                  0  completed without review warnings
                  1  execution or validation error
                  2  invalid command usage
                  3  completed with diagnostics or review warnings
                """);
    }
}

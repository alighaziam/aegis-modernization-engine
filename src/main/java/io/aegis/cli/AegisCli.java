package io.aegis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.intake.IntakeService;
import io.aegis.intake.IntakeToKnowledgeModelMapper;
import io.aegis.parser.sql.SqlDdlParser;
import io.aegis.parser.sql.SqlDdlToKnowledgeMapper;
import io.aegis.parser.java.JavaSourceAnalyzer;
import io.aegis.parser.java.JavaSourceToKnowledgeMapper;

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
                new JavaSourceAnalyzer().analyze(source, sourceFile.getFileName().toString());
        ProjectKnowledgeModel mapped = new JavaSourceToKnowledgeMapper().merge(project, java.util.List.of(analysis));
        System.out.println(JSON.writeValueAsString(new JavaAnalysisOutput(mapped, analysis)));
        return analysis.diagnostics().isEmpty() ? 0 : 3;
    }

    private static boolean isHelp(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equals(arg);
    }

    public record DdlAnalysisOutput(ProjectKnowledgeModel knowledgeModel, SqlDdlParser.ParseResult ddl) {}
    public record JavaAnalysisOutput(ProjectKnowledgeModel knowledgeModel, JavaSourceAnalyzer.AnalysisResult javaAnalysis) {}

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

                Exit codes:
                  0  completed without review warnings
                  1  execution or validation error
                  2  invalid command usage
                  3  completed with diagnostics or review warnings
                """);
    }
}

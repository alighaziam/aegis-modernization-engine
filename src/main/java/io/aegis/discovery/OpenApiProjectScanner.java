package io.aegis.discovery;

import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.parser.openapi.OpenApiYamlAnalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, read-only discovery of OpenAPI JSON/YAML contracts across a workspace. */
public final class OpenApiProjectScanner {
    private static final int MAX_FILES = 10_000;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".vscode", "build", "dist", "node_modules", "out", "target");

    public ScanReport scan(Path projectRoot, ProjectKnowledgeModel baseModel) throws IOException {
        if (projectRoot == null || baseModel == null)
            throw new IllegalArgumentException("projectRoot and baseModel are required");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Project root must be a directory: " + projectRoot);

        List<Path> candidates = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json")) {
                    candidates.add(file);
                    if (candidates.size() > MAX_FILES)
                        throw new ScanLimitException("Workspace exceeds the " + MAX_FILES + " API contract candidate limit.");
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                diagnostics.add("UNREADABLE_PATH: " + relative(root, file) + ": " + failure.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        candidates.sort(Comparator.comparing(path -> relative(root, path)));

        OpenApiYamlAnalyzer analyzer = new OpenApiYamlAnalyzer();
        ProjectKnowledgeModel model = baseModel;
        long bytesRead = 0;
        int analyzed = 0;
        int endpointCount = 0;
        int schemaCount = 0;
        List<OpenApiYamlAnalyzer.Schema> schemas = new ArrayList<>();
        List<SpecificationSummary> specifications = new ArrayList<>();
        for (Path file : candidates) {
            String locator = relative(root, file);
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                diagnostics.add("FILE_SIZE_LIMIT: " + locator + " exceeds " + MAX_FILE_BYTES + " bytes.");
                continue;
            }
            bytesRead += size;
            if (bytesRead > MAX_TOTAL_BYTES)
                throw new ScanLimitException("Workspace exceeds the " + MAX_TOTAL_BYTES + " byte API contract limit.");
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String lower = content.toLowerCase(Locale.ROOT);
            if (!lower.contains("openapi:") && !lower.contains("\"openapi\"")) continue;

            OpenApiYamlAnalyzer.Analysis analysis = analyzer.analyze(content, locator);
            analyzed++;
            endpointCount += analysis.endpoints().size();
            schemaCount += analysis.schemas().size();
            schemas.addAll(analysis.schemas());
            specifications.add(new SpecificationSummary(locator, analysis.openApiVersion(), analysis.title(),
                    analysis.endpoints().size(), analysis.schemas().size()));
            model = analyzer.merge(model, analysis);
            for (String diagnostic : analysis.diagnostics())
                diagnostics.add(locator + ": " + diagnostic);
        }
        return new ScanReport(model, candidates.size(), analyzed, endpointCount, schemaCount,
                bytesRead, specifications, schemas, diagnostics,
                List.of("Only files containing an OpenAPI version marker are parsed.",
                        "External and remote $ref values are not fetched or resolved.",
                        "OpenAPI schemas are reported as contract inventory; they are not promoted to confirmed logical business entities.",
                        "Swagger 2.0 and non-OpenAPI JSON/YAML documents are not interpreted."));
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public record ScanReport(ProjectKnowledgeModel knowledgeModel, int candidatesDiscovered,
                             int specificationsAnalyzed, int endpointsDiscovered,
                             int schemasDiscovered, long bytesRead, List<SpecificationSummary> specifications,
                             List<OpenApiYamlAnalyzer.Schema> schemas, List<String> diagnostics,
                             List<String> limitations) {
        public ScanReport {
            specifications = List.copyOf(specifications);
            schemas = List.copyOf(schemas);
            diagnostics = List.copyOf(diagnostics);
            limitations = List.copyOf(limitations);
        }
    }

    public record SpecificationSummary(String sourceName, String version, String title,
                                        int endpoints, int schemas) {}

    private static final class ScanLimitException extends IOException {
        private ScanLimitException(String message) { super(message); }
    }
}

package io.aegis.discovery;

import io.aegis.analysis.DependencyGraphAnalyzer;
import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.parser.java.JavaSourceAnalyzer;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, read-only discovery across a complete source tree.
 * Symbol resolution, build execution and remote dependency resolution are intentionally excluded.
 */
public final class ProjectSourceScanner {
    private static final int MAX_FILES = 20_000;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".vscode", "build", "dist", "node_modules", "out", "target");

    public ScanReport scan(Path projectRoot, ProjectKnowledgeModel baseModel) throws IOException {
        if (projectRoot == null) throw new IllegalArgumentException("projectRoot is required");
        if (baseModel == null) throw new IllegalArgumentException("baseModel is required");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Project root must be a directory: " + projectRoot);

        List<Path> candidates = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".java") || name.equals("pom.xml")
                        || name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                    candidates.add(file);
                    if (candidates.size() > MAX_FILES)
                        throw new ScanLimitException("Project exceeds the " + MAX_FILES + " discovered-file limit.");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                diagnostics.add("UNREADABLE_PATH: " + relative(root, file) + ": " + failure.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        candidates.sort(Comparator.comparing(path -> relative(root, path)));

        List<JavaSourceAnalyzer.AnalysisResult> analyses = new ArrayList<>();
        Map<String, String> technologyInputs = new LinkedHashMap<>();
        long totalBytes = 0;
        int javaCount = 0;
        int descriptorCount = 0;

        for (Path file : candidates) {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                diagnostics.add("FILE_SIZE_LIMIT: " + relative(root, file) + " exceeds " + MAX_FILE_BYTES + " bytes.");
                continue;
            }
            totalBytes += size;
            if (totalBytes > MAX_TOTAL_BYTES)
                throw new ScanLimitException("Project exceeds the " + MAX_TOTAL_BYTES + " byte discovery limit.");
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String locator = relative(root, file);
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java")) {
                javaCount++;
                technologyInputs.put(locator, content);
                try {
                    JavaSourceAnalyzer.AnalysisResult analysis = new JavaSourceAnalyzer().analyze(content, locator);
                    analyses.add(analysis);
                    for (JavaSourceAnalyzer.ParseDiagnostic issue : analysis.diagnostics())
                        diagnostics.add("JAVA_" + issue.kind() + ": " + locator + ":" + issue.line()
                                + ":" + issue.column() + " " + issue.message());
                } catch (Exception failure) {
                    diagnostics.add("JAVA_ANALYSIS_FAILED: " + locator + ": " + failure.getMessage());
                }
            } else {
                descriptorCount++;
                technologyInputs.put(locator, content);
            }
        }

        ProjectKnowledgeModel mapped = new io.aegis.parser.java.JavaSourceToKnowledgeMapper()
                .merge(baseModel, analyses);
        TechnologyDetector.DetectionReport technology = new TechnologyDetector().detect(technologyInputs);
        Map<String, ProjectKnowledgeModel.Technology> discovered = new LinkedHashMap<>();
        for (ProjectKnowledgeModel.Technology item : mapped.technologies())
            discovered.putIfAbsent(item.name().toLowerCase(Locale.ROOT), item);
        for (TechnologyDetector.TechnologyFinding finding : technology.findings()) {
            discovered.putIfAbsent(finding.name().toLowerCase(Locale.ROOT),
                    new ProjectKnowledgeModel.Technology(finding.name(), finding.version(),
                            finding.role(), ProjectKnowledgeModel.Layer.DISCOVERED));
        }
        mapped = new ProjectKnowledgeModel(mapped.project(), mapped.artifacts(),
                new ArrayList<>(discovered.values()), mapped.components(), mapped.dataStores(),
                mapped.logicalEntities(), mapped.apiEndpoints(), mapped.dependencies(),
                mapped.businessRules(), mapped.analyzedAt(), mapped.modelVersion());

        DependencyGraphAnalyzer.GraphReport graph = new DependencyGraphAnalyzer().analyze(mapped);
        int typeCount = analyses.stream().flatMap(a -> a.files().stream())
                .mapToInt(f -> f.types().size()).sum();
        int importCount = analyses.stream().flatMap(a -> a.files().stream())
                .mapToInt(f -> f.imports().size()).sum();
        return new ScanReport(mapped, candidates.size(), javaCount, analyses.size(), descriptorCount,
                typeCount, importCount, totalBytes, technology, graph, diagnostics,
                List.of("Read-only scan: no source code, build plugins, tests or SQL are executed.",
                        "Java discovery is syntax-based; overload resolution, reflection and runtime wiring are not resolved.",
                        "Only Java files and Maven/Gradle build descriptors are scanned; other languages and dependency lockfiles are not yet analyzed."));
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public record ScanReport(ProjectKnowledgeModel knowledgeModel, int discoveredFiles,
                             int javaFiles, int javaFilesAnalyzed, int buildDescriptors,
                             int declaredTypes, int imports, long bytesRead,
                             TechnologyDetector.DetectionReport technology,
                             DependencyGraphAnalyzer.GraphReport dependencyGraph,
                             List<String> diagnostics, List<String> limitations) {
        public ScanReport {
            diagnostics = List.copyOf(diagnostics);
            limitations = List.copyOf(limitations);
        }
    }

    private static final class ScanLimitException extends IOException {
        private ScanLimitException(String message) { super(message); }
    }
}

package io.aegis.discovery;

import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.parser.sql.SqlDdlParser;
import io.aegis.parser.sql.SqlDdlToKnowledgeMapper;

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

/** Bounded, read-only discovery of SQL DDL files across a project tree. */
public final class DatabaseProjectScanner {
    private static final int MAX_FILES = 10_000;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".vscode", "build", "dist", "node_modules", "out", "target");

    public ScanReport scan(Path projectRoot, ProjectKnowledgeModel baseModel, String dialect,
                           String dataStoreId, String schemaName, String databaseVersion) throws IOException {
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
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                    candidates.add(file);
                    if (candidates.size() > MAX_FILES)
                        throw new ScanLimitException("Project exceeds the " + MAX_FILES + " SQL-file limit.");
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                diagnostics.add("UNREADABLE_PATH: " + relative(root, file) + ": " + failure.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        candidates.sort(Comparator.comparing(path -> relative(root, path)));
        if (candidates.isEmpty())
            throw new IllegalArgumentException("No .sql files were found under: " + projectRoot);

        SqlDdlParser parser = new SqlDdlParser();
        Map<String, SqlDdlParser.Table> tables = new LinkedHashMap<>();
        List<SqlDdlParser.Index> indexes = new ArrayList<>();
        List<SqlDdlParser.EvidenceDraft> evidence = new ArrayList<>();
        int statements = 0;
        long bytesRead = 0;
        String vendor = null;
        for (Path file : candidates) {
            String locator = relative(root, file);
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                diagnostics.add("FILE_SIZE_LIMIT: " + locator + " exceeds " + MAX_FILE_BYTES + " bytes.");
                continue;
            }
            bytesRead += size;
            if (bytesRead > MAX_TOTAL_BYTES)
                throw new ScanLimitException("Project exceeds the " + MAX_TOTAL_BYTES + " byte SQL discovery limit.");
            SqlDdlParser.ParseResult parsed = parser.parse(
                    Files.readString(file, StandardCharsets.UTF_8), locator, dialect);
            if ("UNKNOWN".equals(parsed.vendor()))
                throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect);
            if (vendor != null && !vendor.equals(parsed.vendor()))
                throw new IllegalStateException("Mixed SQL dialects detected during one database scan.");
            vendor = parsed.vendor();
            statements += parsed.statementCount();
            for (SqlDdlParser.Table table : parsed.tables()) {
                String key = table.name().toUpperCase(Locale.ROOT);
                if (tables.putIfAbsent(key, table) != null)
                    diagnostics.add("DUPLICATE_TABLE: " + locator + " defines table " + table.name()
                            + " more than once; the first sorted definition is retained.");
            }
            indexes.addAll(parsed.indexes());
            evidence.addAll(parsed.evidence());
            for (SqlDdlParser.Diagnostic issue : parsed.diagnostics())
                diagnostics.add(issue.action() + ": " + locator + ":" + issue.line()
                        + " " + issue.code() + " " + issue.message());
        }
        if (vendor == null) throw new IllegalArgumentException("No readable SQL files were found.");
        SqlDdlParser.ParseResult combined = new SqlDdlParser.ParseResult(vendor,
                List.copyOf(tables.values()), List.copyOf(indexes), List.of(),
                List.copyOf(evidence), statements);
        ProjectKnowledgeModel mapped = new SqlDdlToKnowledgeMapper().merge(
                baseModel, combined, dataStoreId, schemaName, databaseVersion);
        return new ScanReport(mapped, combined, candidates.size(), bytesRead, diagnostics,
                List.of("Only .sql files and the documented DDL subset are parsed; statements are never executed.",
                        "ALTER TABLE statements can only modify tables created earlier in the same SQL file; cross-file ALTER dependencies require consolidation or manual review.",
                        "Duplicate table definitions retain the first sorted file's definition and are reported as diagnostics.",
                        "Views, routines, triggers, vendor procedural languages, data movement and semantic equivalence are not implemented."));
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public record ScanReport(ProjectKnowledgeModel knowledgeModel, SqlDdlParser.ParseResult ddl,
                             int filesDiscovered, long bytesRead, List<String> diagnostics,
                             List<String> limitations) {
        public ScanReport {
            diagnostics = List.copyOf(diagnostics);
            limitations = List.copyOf(limitations);
        }
    }

    private static final class ScanLimitException extends IOException {
        private ScanLimitException(String message) { super(message); }
    }
}

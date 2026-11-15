package io.aegis.transformation;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Syntax-aware, deterministic import migration for a deliberately small Jakarta EE namespace allowlist.
 * It parses source only: it does not resolve symbols, compile, execute, or load the supplied project.
 */
public final class JavaImportMigrationEngine {
    private static final Map<String, String> PACKAGE_MAPPINGS = Map.of(
            "javax.persistence", "jakarta.persistence",
            "javax.validation", "jakarta.validation",
            "javax.servlet", "jakarta.servlet",
            "javax.transaction", "jakarta.transaction"
    );

    public MigrationResult migrate(String source, String sourceName) throws Exception {
        if (source == null) throw new IllegalArgumentException("Java source is required");
        if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("sourceName is required");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject input = new Source(sourceName, source);
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics,
                List.of("-proc:none", "-Xlint:none"), null, List.of(input));
        Trees trees = Trees.instance(task);
        SourcePositions positions = trees.getSourcePositions();
        List<Replacement> replacements = new ArrayList<>();
        for (CompilationUnitTree unit : task.parse()) {
            for (ImportTree declaration : unit.getImports()) {
                if (declaration.isStatic()) continue;
                String imported = declaration.getQualifiedIdentifier().toString();
                String migrated = mapImport(imported);
                if (migrated == null) continue;
                long start = positions.getStartPosition(unit, declaration);
                long end = positions.getEndPosition(unit, declaration);
                if (start < 0 || end < start || end > source.length())
                    throw new IllegalStateException("Compiler did not provide safe source positions for import " + imported);
                replacements.add(new Replacement((int) start, (int) end,
                        source.substring((int) start, (int) end),
                        "import " + migrated + ";", imported, migrated));
            }
        }
        List<ParseIssue> issues = diagnostics.getDiagnostics().stream()
                .map(d -> new ParseIssue(d.getKind().name(), d.getMessage(java.util.Locale.ROOT),
                        d.getLineNumber(), d.getColumnNumber()))
                .toList();
        if (issues.stream().anyMatch(i -> "ERROR".equals(i.kind())))
            return new MigrationResult(source, List.of(), issues, false,
                    "Source has syntax errors; no migration was produced.");
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        StringBuilder changed = new StringBuilder(source);
        for (Replacement replacement : replacements)
            changed.replace(replacement.start(), replacement.end(), replacement.after());
        List<Replacement> ordered = replacements.stream()
                .sorted(Comparator.comparingInt(Replacement::start)).toList();
        return new MigrationResult(changed.toString(), ordered, issues, true,
                "Import syntax was migrated using the allowlist. Type resolution and dependency compatibility were not checked.");
    }

    private static String mapImport(String imported) {
        for (var mapping : PACKAGE_MAPPINGS.entrySet()) {
            String from = mapping.getKey();
            if (imported.equals(from) || imported.startsWith(from + "."))
                return mapping.getValue() + imported.substring(from.length());
        }
        return null;
    }

    public record Replacement(int start, int end, String before, String after,
                              String sourceImport, String targetImport) {}
    public record ParseIssue(String kind, String message, long line, long column) {}
    public record MigrationResult(String transformedSource, List<Replacement> replacements,
                                  List<ParseIssue> diagnostics, boolean safeToReview,
                                  String limitation) {
        public MigrationResult {
            replacements = List.copyOf(replacements);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String content;
        private Source(String name, String content) {
            super(URI.create("string:///" + safeName(name)), Kind.SOURCE);
            this.content = content;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return content; }
        private static String safeName(String name) {
            String base = name.replace('\\', '/');
            base = base.substring(base.lastIndexOf('/') + 1);
            return base.endsWith(".java") ? base : base + ".java";
        }
    }
}

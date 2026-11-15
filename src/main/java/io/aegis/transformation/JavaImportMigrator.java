package io.aegis.transformation;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Syntax-aware, deterministic import migration for a deliberately small Jakarta namespace allowlist.
 * This performs no symbol resolution and never rewrites arbitrary source text.
 */
public final class JavaImportMigrator {
    private static final Map<String, String> PACKAGE_MAPPINGS = Map.of(
            "javax.persistence", "jakarta.persistence",
            "javax.validation", "jakarta.validation",
            "javax.servlet", "jakarta.servlet"
    );

    public MigrationPlan plan(String source, String sourceName) {
        if (source == null) throw new IllegalArgumentException("source cannot be null");
        String safeName = sourceName == null || sourceName.isBlank() ? "Input.java" : sourceName;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required; Java compiler API is unavailable");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject input = new StringJavaFileObject(safeName, source);
        List<ImportEdit> edits = new ArrayList<>();
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics,
                    List.of("-proc:none", "-Xlint:none"), null, List.of(input));
            for (CompilationUnitTree unit : task.parse()) {
                SourcePositions positions = Trees.instance(task).getSourcePositions();
                for (ImportTree importTree : unit.getImports()) {
                    String qualified = importTree.getQualifiedIdentifier().toString();
                    String replacement = migrateQualifiedName(qualified);
                    if (!qualified.equals(replacement)) {
                        long start = positions.getStartPosition(unit, importTree);
                        long end = positions.getEndPosition(unit, importTree);
                        if (start < 0 || end < start || end > source.length()) {
                            return failedPlan(source, "UNAVAILABLE_SOURCE_RANGE",
                                    "Compiler did not provide a safe source range for import " + qualified);
                        }
                        String original = source.substring((int) start, (int) end);
                        String rewritten = original.replace(qualified, replacement);
                        edits.add(new ImportEdit((int) start, (int) end, qualified, replacement, original, rewritten));
                    }
                }
            }
        } catch (Exception exception) {
            return failedPlan(source, "JAVA_PARSE_FAILED", exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }

        List<String> parseErrors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> "line " + d.getLineNumber() + ": " + d.getMessage(null))
                .toList();
        if (!parseErrors.isEmpty()) {
            return new MigrationPlan(source, List.of(), List.of(new Diagnostic("JAVA_PARSE_ERRORS",
                    "Source contains syntax errors; no import changes were produced", parseErrors)), false);
        }

        edits.sort(Comparator.comparingInt(ImportEdit::start).reversed());
        StringBuilder migrated = new StringBuilder(source);
        for (ImportEdit edit : edits) {
            migrated.replace(edit.start(), edit.end(), edit.rewrittenText());
        }
        List<AppliedRule> rules = edits.stream()
                .map(e -> new AppliedRule(e.from(), e.to(), "JAKARTA_NAMESPACE_IMPORT"))
                .toList();
        return new MigrationPlan(migrated.toString(), rules, List.of(), true);
    }

    private static String migrateQualifiedName(String qualified) {
        for (Map.Entry<String, String> mapping : PACKAGE_MAPPINGS.entrySet()) {
            if (qualified.equals(mapping.getKey()) || qualified.startsWith(mapping.getKey() + ".")) {
                return mapping.getValue() + qualified.substring(mapping.getKey().length());
            }
        }
        return qualified;
    }

    private static MigrationPlan failedPlan(String source, String code, String message) {
        return new MigrationPlan(source, List.of(),
                List.of(new Diagnostic(code, message, List.of())), false);
    }

    public record MigrationPlan(String migratedSource, List<AppliedRule> appliedRules,
                               List<Diagnostic> diagnostics, boolean safeToWrite) {
        public MigrationPlan {
            appliedRules = List.copyOf(appliedRules);
            diagnostics = List.copyOf(diagnostics);
        }
        public boolean changed() { return !appliedRules.isEmpty(); }
    }

    public record AppliedRule(String fromImport, String toImport, String ruleId) {}
    public record Diagnostic(String code, String message, List<String> details) {
        public Diagnostic { details = List.copyOf(details); }
    }
    private record ImportEdit(int start, int end, String from, String to,
                              String originalText, String rewrittenText) {}

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;
        private StringJavaFileObject(String name, String source) {
            super(URI.create("string:///" + sanitize(name)), Kind.SOURCE);
            this.source = source;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        private static String sanitize(String name) {
            String normalized = name.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            String file = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            return file.endsWith(".java") ? file : file + ".java";
        }
    }
}

package io.aegis.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extracts syntactic behavior signals from Java AST without compiling or executing source. */
public final class JavaBehaviorAnalyzer {
    public BehaviorReport analyze(String source, String sourceName) {
        if (source == null) throw new IllegalArgumentException("Java source is required");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK compiler is required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<MethodCall> calls = new ArrayList<>();
        List<GuardCondition> guards = new ArrayList<>();
        List<ThrownException> throwsFound = new ArrayList<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none"), null, List.of(new Source(sourceName, source)));
            for (CompilationUnitTree unit : task.parse()) {
                SourcePositions positions = Trees.instance(task).getSourcePositions();
                new TreePathScanner<Void, Void>() {
                    private String type = "<top-level>";
                    private String method = "<initializer>";
                    private long line(Tree node) {
                        long p = positions.getStartPosition(unit, node);
                        return p < 0 || unit.getLineMap() == null ? 1 : unit.getLineMap().getLineNumber(p);
                    }
                    @Override public Void visitClass(com.sun.source.tree.ClassTree node, Void unused) {
                        String previous = type;
                        type = node.getSimpleName().toString().isBlank() ? "<anonymous>" : node.getSimpleName().toString();
                        try { return super.visitClass(node, unused); } finally { type = previous; }
                    }
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        String previous = method;
                        method = node.getName().toString();
                        try { return super.visitMethod(node, unused); } finally { method = previous; }
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        String expression = node.getMethodSelect().toString();
                        int dot = expression.lastIndexOf('.');
                        String receiver = dot > 0 ? expression.substring(0, dot) : null;
                        calls.add(new MethodCall(type, method, expression, receiver,
                                node.getArguments().size(), line(node)));
                        return super.visitMethodInvocation(node, unused);
                    }
                    @Override public Void visitIf(IfTree node, Void unused) {
                        guards.add(new GuardCondition(type, method, node.getCondition().toString(), line(node)));
                        return super.visitIf(node, unused);
                    }
                    @Override public Void visitThrow(ThrowTree node, Void unused) {
                        throwsFound.add(new ThrownException(type, method, node.getExpression().toString(), line(node)));
                        return super.visitThrow(node, unused);
                    }
                }.scan(unit, null);
            }
            List<String> parseDiagnostics = diagnostics.getDiagnostics().stream()
                    .map(d -> d.getKind().name() + ":" + d.getLineNumber() + ":" + d.getMessage(Locale.ROOT))
                    .toList();
            return new BehaviorReport(calls, guards, throwsFound, parseDiagnostics,
                    List.of("Method receivers and guards are syntactic candidates; overload resolution, data flow and runtime dispatch are not resolved."));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to analyze Java behavior: " + e.getMessage(), e);
        }
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String text;
        private Source(String name, String text) {
            super(URI.create("string:///" + safe(name)), Kind.SOURCE);
            this.text = text;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
        private static String safe(String name) {
            String value = name == null ? "Source.java" : name.replace('\\', '/');
            int slash = value.lastIndexOf('/');
            value = slash >= 0 ? value.substring(slash + 1) : value;
            value = value.replaceAll("[^A-Za-z0-9._-]", "_");
            return value.endsWith(".java") ? value : value + ".java";
        }
    }

    public record BehaviorReport(List<MethodCall> methodCalls, List<GuardCondition> guards,
                                List<ThrownException> thrownExceptions, List<String> diagnostics,
                                List<String> limitations) {
        public BehaviorReport {
            methodCalls = List.copyOf(methodCalls); guards = List.copyOf(guards);
            thrownExceptions = List.copyOf(thrownExceptions); diagnostics = List.copyOf(diagnostics);
            limitations = List.copyOf(limitations);
        }
    }
    public record MethodCall(String ownerType, String ownerMethod, String expression,
                             String receiverCandidate, int argumentCount, long line) {}
    public record GuardCondition(String ownerType, String ownerMethod, String condition, long line) {}
    public record ThrownException(String ownerType, String ownerMethod, String expression, long line) {}
}

package io.aegis.parser.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses Java source with the JDK compiler tree API. It never compiles, loads,
 * executes, or runs annotation processors from the supplied source.
 */
public final class JavaSourceAnalyzer {

    public AnalysisResult analyze(String source, String sourceName) throws Exception {
        if (source == null) throw new IllegalArgumentException("Java source is required");
        if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("sourceName is required");

        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required; no Java compiler is available");

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        JavaFileObject input = new StringSource(sourceName, source);
        JavacTask task = (JavacTask) compiler.getTask(null, null, collector,
                List.of("-proc:none", "-Xlint:none"), null, List.of(input));

        List<SourceFile> files = new ArrayList<>();
        for (CompilationUnitTree unit : task.parse()) {
            String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            List<ImportDeclaration> imports = new ArrayList<>();
            for (ImportTree item : unit.getImports()) {
                imports.add(new ImportDeclaration(item.isStatic(),
                        item.getQualifiedIdentifier().toString(),
                        line(unit, item)));
            }

            List<TypeDeclaration> types = new ArrayList<>();
            Deque<String> parents = new ArrayDeque<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    String simpleName = node.getSimpleName().toString();
                    if (simpleName.isBlank()) return super.visitClass(node, unused);
                    String parent = parents.peekLast();
                    String qualifiedName = parent != null ? parent + "." + simpleName
                            : packageName.isBlank() ? simpleName : packageName + "." + simpleName;
                    types.add(new TypeDeclaration(qualifiedName, simpleName,
                            node.getKind().name().toLowerCase(Locale.ROOT),
                            node.getModifiers().getFlags().stream().map(f -> f.name().toLowerCase(Locale.ROOT)).sorted().toList(),
                            node.getExtendsClause() == null ? null : node.getExtendsClause().toString(),
                            node.getImplementsClause().stream().map(Tree::toString).toList(),
                            line(unit, node)));
                    parents.addLast(qualifiedName);
                    try {
                        return super.visitClass(node, unused);
                    } finally {
                        parents.removeLast();
                    }
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    String owner = parents.peekLast();
                    if (owner != null) {
                        String parameters = node.getParameters().stream()
                                .map(parameter -> parameter.getType() == null ? "?" : parameter.getType().toString())
                                .collect(Collectors.joining(","));
                        String returnType = node.getReturnType() == null ? "<constructor>" : node.getReturnType().toString();
                        types.stream().filter(type -> type.qualifiedName().equals(owner)).findFirst()
                                .ifPresent(type -> type.addMethod(new MethodDeclaration(
                                        node.getName().toString(), parameters, returnType, line(unit, node))));
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);

            files.add(new SourceFile(sourceName, packageName, imports, types));
        }

        List<ParseDiagnostic> diagnostics = collector.getDiagnostics().stream()
                .map(d -> new ParseDiagnostic(d.getKind().name(),
                        d.getCode(), d.getMessage(Locale.ROOT),
                        d.getLineNumber(), d.getColumnNumber()))
                .toList();
        return new AnalysisResult(files, diagnostics);
    }

    private static long line(CompilationUnitTree unit, Tree tree) {
        long position = unit.getLineMap().getLineNumber(
                unit.getSourceFile() == null ? 0 : 0);
        return position < 1 ? 1 : position;
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String source;

        private StringSource(String sourceName, String source) {
            super(URI.create("string:///" + safeFileName(sourceName)), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }

        private static String safeFileName(String sourceName) {
            String name = sourceName.replace('\\', '/');
            int slash = name.lastIndexOf('/');
            name = slash >= 0 ? name.substring(slash + 1) : name;
            name = name.replaceAll("[^A-Za-z0-9._-]", "_");
            return name.endsWith(".java") ? name : name + ".java";
        }
    }

    public record AnalysisResult(List<SourceFile> files, List<ParseDiagnostic> diagnostics) {
        public AnalysisResult {
            files = List.copyOf(files);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record SourceFile(String sourceName, String packageName,
                             List<ImportDeclaration> imports, List<TypeDeclaration> types) {
        public SourceFile {
            imports = List.copyOf(imports);
            types = List.copyOf(types);
        }
    }

    public record ImportDeclaration(boolean isStatic, String qualifiedName, long line) {}

    public record TypeDeclaration(String qualifiedName, String name, String kind,
                                  List<String> modifiers, String extendsType,
                                  List<String> implementsTypes, long line,
                                  List<MethodDeclaration> methods) {
        public TypeDeclaration(String qualifiedName, String name, String kind, List<String> modifiers,
                               String extendsType, List<String> implementsTypes, long line) {
            this(qualifiedName, name, kind, modifiers, extendsType, implementsTypes, line, new ArrayList<>());
        }
        public TypeDeclaration {
            modifiers = List.copyOf(modifiers);
            implementsTypes = List.copyOf(implementsTypes);
            methods = new ArrayList<>(methods);
        }
        private void addMethod(MethodDeclaration method) {
            methods.add(method);
        }
    }

    public record MethodDeclaration(String name, String parameterTypes, String returnType, long line) {}

    public record ParseDiagnostic(String kind, String code, String message, long line, long column) {}
}

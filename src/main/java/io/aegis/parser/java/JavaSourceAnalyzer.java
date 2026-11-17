package io.aegis.parser.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

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
        Trees trees = Trees.instance(task);
        SourcePositions positions = trees.getSourcePositions();

        List<SourceFile> files = new ArrayList<>();
        for (CompilationUnitTree unit : task.parse()) {
            String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            List<ImportDeclaration> imports = new ArrayList<>();
            for (ImportTree item : unit.getImports()) {
                imports.add(new ImportDeclaration(item.isStatic(),
                        item.getQualifiedIdentifier().toString(),
                        line(unit, item, positions)));
            }

            List<TypeBuilder> builders = new ArrayList<>();
            Deque<TypeBuilder> parents = new ArrayDeque<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    String simpleName = node.getSimpleName().toString();
                    if (simpleName.isBlank()) {
                        TypeBuilder enclosing = parents.peekLast();
                        String owner = enclosing == null ? packageName : enclosing.qualifiedName;
                        TypeBuilder anonymous = new TypeBuilder(owner + ".$anonymous$" + line(unit, node, positions),
                                "$anonymous", "anonymous", List.of(), null, List.of(), line(unit, node, positions));
                        parents.addLast(anonymous);
                        try {
                            return super.visitClass(node, unused);
                        } finally {
                            parents.removeLast();
                        }
                    }
                    TypeBuilder parent = parents.peekLast();
                    String qualifiedName = parent != null ? parent.qualifiedName + "." + simpleName
                            : packageName.isBlank() ? simpleName : packageName + "." + simpleName;
                    TypeBuilder current = new TypeBuilder(qualifiedName, simpleName,
                            node.getKind().name().toLowerCase(Locale.ROOT),
                            node.getModifiers().getFlags().stream()
                                    .map(f -> f.name().toLowerCase(Locale.ROOT)).sorted().toList(),
                            node.getExtendsClause() == null ? null : node.getExtendsClause().toString(),
                            node.getImplementsClause().stream().map(Tree::toString).toList(),
                            line(unit, node, positions));
                    builders.add(current);
                    parents.addLast(current);
                    try {
                        return super.visitClass(node, unused);
                    } finally {
                        parents.removeLast();
                    }
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    TypeBuilder owner = parents.peekLast();
                    var parentPath = getCurrentPath() == null ? null : getCurrentPath().getParentPath();
                    if (owner != null && parentPath != null
                            && parentPath.getLeaf() instanceof ClassTree
                            && node.getType() != null) {
                        owner.fields.add(new FieldDeclaration(node.getName().toString(),
                                node.getType().toString(), line(unit, node, positions)));
                    }
                    return super.visitVariable(node, unused);
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    TypeBuilder owner = parents.peekLast();
                    if (owner != null) {
                        String parameters = node.getParameters().stream()
                                .map(parameter -> parameter.getType() == null ? "?" : parameter.getType().toString())
                                .collect(java.util.stream.Collectors.joining(","));
                        String returnType = node.getReturnType() == null ? "<constructor>" : node.getReturnType().toString();
                        owner.methods.add(new MethodDeclaration(node.getName().toString(), parameters,
                                returnType, line(unit, node, positions)));
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);

            files.add(new SourceFile(sourceName, packageName, imports,
                    builders.stream().map(TypeBuilder::build).toList()));
        }

        List<ParseDiagnostic> diagnostics = collector.getDiagnostics().stream()
                .map(d -> new ParseDiagnostic(d.getKind().name(), d.getCode(), d.getMessage(Locale.ROOT),
                        d.getLineNumber(), d.getColumnNumber()))
                .toList();
        return new AnalysisResult(files, diagnostics);
    }

    private static long line(CompilationUnitTree unit, Tree tree, SourcePositions positions) {
        long position = positions.getStartPosition(unit, tree);
        if (position < 0 || unit.getLineMap() == null) return 1;
        long line = unit.getLineMap().getLineNumber(position);
        return line < 1 ? 1 : line;
    }

    private static final class TypeBuilder {
        private final String qualifiedName;
        private final String name;
        private final String kind;
        private final List<String> modifiers;
        private final String extendsType;
        private final List<String> implementsTypes;
        private final long line;
        private final List<MethodDeclaration> methods = new ArrayList<>();
        private final List<FieldDeclaration> fields = new ArrayList<>();

        private TypeBuilder(String qualifiedName, String name, String kind, List<String> modifiers,
                            String extendsType, List<String> implementsTypes, long line) {
            this.qualifiedName = qualifiedName;
            this.name = name;
            this.kind = kind;
            this.modifiers = modifiers;
            this.extendsType = extendsType;
            this.implementsTypes = implementsTypes;
            this.line = line;
        }

        private TypeDeclaration build() {
            return new TypeDeclaration(qualifiedName, name, kind, modifiers, extendsType,
                    implementsTypes, line, methods, fields);
        }
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
                                  List<MethodDeclaration> methods, List<FieldDeclaration> fields) {
        public TypeDeclaration {
            modifiers = List.copyOf(modifiers);
            implementsTypes = List.copyOf(implementsTypes);
            methods = List.copyOf(methods);
            fields = List.copyOf(fields);
        }
    }

    public record MethodDeclaration(String name, String parameterTypes, String returnType, long line) {}

    public record FieldDeclaration(String name, String type, long line) {}

    public record ParseDiagnostic(String kind, String code, String message, long line, long column) {}
}

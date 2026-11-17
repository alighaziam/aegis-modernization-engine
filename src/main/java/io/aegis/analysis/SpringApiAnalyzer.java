package io.aegis.analysis;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Extracts explicit Spring MVC/WebFlux route annotations without executing or resolving application code. */
public final class SpringApiAnalyzer {
    private static final int MAX_FILES = 10_000;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final String WEB_ANNOTATIONS = "org.springframework.web.bind.annotation.";
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", ".vscode", "build", "dist", "node_modules", "out", "target");
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");

    public Analysis analyzeProject(Path projectRoot) throws IOException {
        if (projectRoot == null) throw new IllegalArgumentException("projectRoot is required");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Project root must be a directory");
        List<Path> files = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && file.getFileName().toString().endsWith(".java")) {
                    files.add(file);
                    if (files.size() > MAX_FILES) throw new IOException("Spring API scan exceeds " + MAX_FILES + " Java files");
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                diagnostics.add("UNREADABLE_PATH: " + relative(root, file) + ": " + failure.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> relative(root, path)));
        List<EndpointMapping> endpoints = new ArrayList<>();
        long bytesRead = 0;
        for (Path file : files) {
            String locator = relative(root, file);
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                diagnostics.add("FILE_SIZE_LIMIT: " + locator + " exceeds " + MAX_FILE_BYTES + " bytes");
                continue;
            }
            bytesRead += size;
            if (bytesRead > MAX_TOTAL_BYTES) throw new IOException("Spring API scan exceeds " + MAX_TOTAL_BYTES + " bytes");
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                ParseResult parsed = parse(source, locator);
                endpoints.addAll(parsed.endpoints());
                diagnostics.addAll(parsed.diagnostics());
            } catch (RuntimeException failure) {
                diagnostics.add("JAVA_PARSE_FAILED: " + locator + ": " + failure.getMessage());
            } catch (IOException unreadable) {
                diagnostics.add("UNREADABLE_FILE: " + locator + ": " + unreadable.getMessage());
            }
        }
        endpoints.sort(Comparator.comparing(EndpointMapping::controller)
                .thenComparing(EndpointMapping::httpMethod).thenComparing(EndpointMapping::path)
                .thenComparing(EndpointMapping::methodName));
        return new Analysis(endpoints, diagnostics, files.size(), bytesRead,
                List.of("Only explicit Spring @RestController/@Controller and mapping annotations are discovered.",
                        "Composed/custom annotations, interface-level mappings, inherited mappings and route conditions are not expanded.",
                        "Request parameters, path-variable types, response schemas and runtime handler registration are not inferred.",
                        "No application code, Spring context or annotation processor is executed."));
    }

    public ParseResult parse(String source, String sourceName) {
        if (source == null || sourceName == null) throw new IllegalArgumentException("source and sourceName are required");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK compiler is required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + sourceName.replace('\\', '/')),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        List<EndpointMapping> endpoints = new ArrayList<>();
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics,
                    List.of("-proc:none", "-Xlint:none"), null, List.of(file));
            CompilationUnitTree unit = task.parse().iterator().next();
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            Set<String> imports = new LinkedHashSet<>();
            unit.getImports().forEach(item -> imports.add(item.getQualifiedIdentifier().toString()));
            String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();

            new TreePathScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree node, Void unused) {
                    if (isController(node.getModifiers().getAnnotations(), imports)) {
                        String controller = packageName.isBlank() ? node.getSimpleName().toString()
                                : packageName + "." + node.getSimpleName();
                        List<String> prefixes = mappingPaths(node.getModifiers().getAnnotations(), imports, "RequestMapping");
                        if (prefixes.isEmpty()) prefixes = List.of("");
                        boolean restController = hasAnnotation(node.getModifiers().getAnnotations(), imports, "RestController");
                        boolean controllerResponseBody = hasAnnotation(node.getModifiers().getAnnotations(), imports, "ResponseBody");
                        for (var member : node.getMembers()) {
                            if (!(member instanceof MethodTree method)) continue;
                            boolean methodResponseBody = hasAnnotation(method.getModifiers().getAnnotations(), imports, "ResponseBody");
                            if (!restController && !controllerResponseBody && !methodResponseBody) continue;
                            for (AnnotationTree annotation : method.getModifiers().getAnnotations()) {
                                String simple = simpleName(annotation);
                                if (!MAPPING_ANNOTATIONS.contains(simple)
                                        || !isSpringAnnotation(annotation, imports, simple)) continue;
                                Mapping mapping = mapping(annotation, imports, simple);
                                long line = line(unit, method, positions);
                                for (String prefix : prefixes) {
                                    for (String suffix : mapping.paths()) {
                                        for (String verb : mapping.methods()) {
                                            endpoints.add(new EndpointMapping(controller, method.getName().toString(),
                                                    verb, joinPath(prefix, suffix), locator, line,
                                                    annotation.getAnnotationType().toString()));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return super.visitClass(node, unused);
                }
            }.scan(unit, null);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Could not parse Java source " + sourceName, failure);
        }
        for (var diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                return new ParseResult(endpoints, List.of("JAVA_PARSE_ERROR: " + sourceName + ": " + diagnostic.getMessage(Locale.ROOT)));
        }
        return new ParseResult(endpoints, List.of());
    }

    private static boolean isController(List<? extends AnnotationTree> annotations, Set<String> imports) {
        return hasAnnotation(annotations, imports, "RestController")
                || hasAnnotation(annotations, imports, "Controller");
    }

    private static boolean hasAnnotation(List<? extends AnnotationTree> annotations, Set<String> imports, String name) {
        return annotations.stream().anyMatch(annotation -> isSpringAnnotation(annotation, imports, name));
    }

    private static boolean isSpringAnnotation(AnnotationTree annotation, Set<String> imports, String simple) {
        String type = annotation.getAnnotationType().toString();
        if (type.equals(WEB_ANNOTATIONS + simple)) return true;
        if ("Controller".equals(simple) && type.equals("org.springframework.stereotype.Controller")) return true;
        if (!simpleName(annotation).equals(simple)) return false;
        return imports.contains(WEB_ANNOTATIONS + simple) || imports.contains(WEB_ANNOTATIONS + "*")
                || "Controller".equals(simple) && (imports.contains("org.springframework.stereotype.Controller")
                || imports.contains("org.springframework.stereotype.*"));
    }

    private static String simpleName(AnnotationTree annotation) {
        String type = annotation.getAnnotationType().toString();
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private static List<String> mappingPaths(List<? extends AnnotationTree> annotations, Set<String> imports, String expected) {
        return annotations.stream().filter(annotation -> simpleName(annotation).equals(expected)
                        && isSpringAnnotation(annotation, imports, expected))
                .findFirst().map(annotation -> mapping(annotation, imports, expected).paths()).orElse(List.of());
    }

    private static Mapping mapping(AnnotationTree annotation, Set<String> imports, String simple) {
        List<String> paths = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        switch (simple) {
            case "GetMapping" -> methods.add("GET");
            case "PostMapping" -> methods.add("POST");
            case "PutMapping" -> methods.add("PUT");
            case "PatchMapping" -> methods.add("PATCH");
            case "DeleteMapping" -> methods.add("DELETE");
            default -> methods.add("ANY");
        }
        for (ExpressionTree argument : annotation.getArguments()) {
            String name = null;
            ExpressionTree value = argument;
            if (argument instanceof AssignmentTree assignment) {
                name = assignment.getVariable().toString();
                value = assignment.getExpression();
            }
            if ("value".equals(name) || "path".equals(name) || name == null && paths.isEmpty())
                collectStrings(value, paths);
            if ("method".equals(name)) {
                methods.clear();
                collectMethods(value, methods);
            }
        }
        if (paths.isEmpty()) paths.add("");
        if (methods.isEmpty()) methods.add("ANY");
        return new Mapping(paths.stream().distinct().toList(), methods.stream().distinct().toList());
    }

    private static void collectStrings(ExpressionTree value, List<String> output) {
        if (value instanceof LiteralTree literal && literal.getValue() instanceof String string) output.add(string);
        else if (value instanceof NewArrayTree array && array.getInitializers() != null)
            array.getInitializers().forEach(item -> collectStrings(item, output));
    }

    private static void collectMethods(ExpressionTree value, List<String> output) {
        if (value instanceof MemberSelectTree member) output.add(member.getIdentifier().toString().toUpperCase(Locale.ROOT));
        else if (value instanceof IdentifierTree identifier) output.add(identifier.getName().toString().toUpperCase(Locale.ROOT));
        else if (value instanceof NewArrayTree array && array.getInitializers() != null)
            array.getInitializers().forEach(item -> collectMethods(item, output));
    }

    private static String joinPath(String prefix, String suffix) {
        String left = prefix == null ? "" : prefix.trim();
        String right = suffix == null ? "" : suffix.trim();
        String joined = ("/" + left + "/" + right).replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) joined = joined.substring(0, joined.length() - 1);
        return joined;
    }

    private static long line(CompilationUnitTree unit, MethodTree method, SourcePositions positions) {
        long position = positions.getStartPosition(unit, method);
        return position < 0 ? -1 : unit.getLineMap().getLineNumber(position);
    }

    private static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }

    public record EndpointMapping(String controller, String methodName, String httpMethod, String path,
                                  String sourceLocator, long line, String annotation) {}
    public record ParseResult(List<EndpointMapping> endpoints, List<String> diagnostics) {
        public ParseResult { endpoints = List.copyOf(endpoints); diagnostics = List.copyOf(diagnostics); }
    }
    public record Analysis(List<EndpointMapping> endpoints, List<String> diagnostics, int filesScanned,
                          long bytesRead, List<String> limitations) {
        public Analysis { endpoints = List.copyOf(endpoints); diagnostics = List.copyOf(diagnostics); limitations = List.copyOf(limitations); }
    }
    private record Mapping(List<String> paths, List<String> methods) {}
}

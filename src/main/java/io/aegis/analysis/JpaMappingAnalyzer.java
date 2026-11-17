package io.aegis.analysis;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts explicit Jakarta/Javax Persistence mapping declarations from Java ASTs.
 * It does not load classes, run annotation processors, infer mappings or connect to a database.
 */
public final class JpaMappingAnalyzer {
    private static final int MAX_FILES = 10_000;
    private static final long MAX_FILE_BYTES = 1_048_576L;
    private static final long MAX_TOTAL_BYTES = 64L * 1_048_576L;
    private static final List<String> RELATIONSHIPS = List.of(
            "OneToOne", "OneToMany", "ManyToOne", "ManyToMany");

    public MappingReport analyze(Path sourceRoot) throws java.io.IOException {
        if (sourceRoot == null) throw new IllegalArgumentException("sourceRoot is required");
        Path root = sourceRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Source root must be a directory");
        List<Path> files = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && file.getFileName().toString().endsWith(".java")) {
                    files.add(file);
                    if (files.size() > MAX_FILES)
                        throw new java.io.IOException("JPA scan exceeds " + MAX_FILES + " Java files");
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, java.io.IOException failure) {
                diagnostics.add("UNREADABLE_PATH: " + root.relativize(file) + ": " + failure.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> root.relativize(path).toString()));

        List<EntityMapping> entities = new ArrayList<>();
        long totalBytes = 0;
        for (Path file : files) {
            long size = Files.size(file);
            String locator = root.relativize(file).toString().replace('\\', '/');
            if (size > MAX_FILE_BYTES) {
                diagnostics.add("FILE_SIZE_LIMIT: " + locator + " exceeds " + MAX_FILE_BYTES + " bytes");
                continue;
            }
            totalBytes += size;
            if (totalBytes > MAX_TOTAL_BYTES)
                throw new java.io.IOException("JPA scan exceeds " + MAX_TOTAL_BYTES + " bytes");
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                ParseOutcome outcome = parse(source, locator);
                entities.addAll(outcome.entities());
                diagnostics.addAll(outcome.diagnostics());
            } catch (RuntimeException failure) {
                diagnostics.add("JAVA_PARSE_FAILED: " + locator + ": " + failure.getMessage());
            } catch (java.io.IOException unreadable) {
                diagnostics.add("UNREADABLE_FILE: " + locator + ": " + unreadable.getMessage());
            }
        }
        return correlate(entities, diagnostics, files.size(), totalBytes);
    }

    private static ParseOutcome parse(String source, String sourceName) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required");
        var collector = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject input = new Source(sourceName, source);
        JavacTask task = (JavacTask) compiler.getTask(null, null, collector,
                List.of("-proc:none", "-Xlint:none"), null, List.of(input));
        List<EntityMapping> entities = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try {
            for (CompilationUnitTree unit : task.parse()) {
                String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                Set<String> imports = new LinkedHashSet<>();
                unit.getImports().forEach(item -> imports.add(item.getQualifiedIdentifier().toString()));
                new TreePathScanner<Void, Void>() {
                    @Override public Void visitClass(ClassTree node, Void unused) {
                        Map<String, AnnotationTree> annotations = annotations(node.getModifiers(), imports);
                        if (!annotations.containsKey("Entity")) return super.visitClass(node, unused);
                        String simpleName = node.getSimpleName().toString();
                        if (simpleName.isBlank()) return super.visitClass(node, unused);
                        String qualified = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
                        Map<String, String> tableArgs = values(annotations.get("Table"));
                        String table = tableArgs.getOrDefault("name", simpleName);
                        String schema = tableArgs.get("schema");
                        List<RelationshipMapping> relationships = new ArrayList<>();
                        List<ColumnMapping> columns = new ArrayList<>();
                        for (Tree member : node.getMembers()) {
                            if (!(member instanceof VariableTree field)) continue;
                            Map<String, AnnotationTree> fieldAnnotations = annotations(field.getModifiers(), imports);
                            for (String name : List.of("Column", "JoinColumn", "JoinColumns", "Id", "EmbeddedId")) {
                                AnnotationTree annotation = fieldAnnotations.get(name);
                                if (annotation != null) {
                                    if (name.equals("JoinColumns")) {
                                        for (AnnotationTree nested : nestedAnnotations(annotation))
                                            if (annotationName(nested).equals("JoinColumn"))
                                                columns.add(column(field.getName().toString(), nested));
                                    } else columns.add(column(field.getName().toString(), annotation));
                                }
                            }
                            String relationship = RELATIONSHIPS.stream()
                                    .filter(fieldAnnotations::containsKey).findFirst().orElse(null);
                            if (relationship != null) {
                                AnnotationTree annotation = fieldAnnotations.get(relationship);
                                Map<String, String> args = values(annotation);
                                String target = args.get("targetEntity");
                                if (target != null) target = target.replace(".class", "");
                                if (target == null) target = relationshipTarget(field.getType() == null ? "" : field.getType().toString(), relationship);
                                List<ColumnMapping> joins = new ArrayList<>();
                                AnnotationTree join = fieldAnnotations.get("JoinColumn");
                                if (join != null) joins.add(column(field.getName().toString(), join));
                                AnnotationTree joinsAnnotation = fieldAnnotations.get("JoinColumns");
                                if (joinsAnnotation != null)
                                    for (AnnotationTree nested : nestedAnnotations(joinsAnnotation))
                                        if (annotationName(nested).equals("JoinColumn"))
                                            joins.add(column(field.getName().toString(), nested));
                                relationships.add(new RelationshipMapping(field.getName().toString(), relationship,
                                        target, args.getOrDefault("mappedBy", ""), joins, sourceName, FactStatus.OBSERVED));
                            }
                        }
                        entities.add(new EntityMapping(qualified, simpleName, packageName, List.copyOf(imports),
                                table, schema, columns, relationships, sourceName, FactStatus.OBSERVED));
                        return super.visitClass(node, unused);
                    }
                }.scan(unit, null);
            }
            collector.getDiagnostics().stream().filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .forEach(d -> diagnostics.add("JAVA_PARSE_ERROR: " + sourceName + ":" + d.getLineNumber()
                            + " " + d.getMessage(Locale.ROOT)));
        } catch (Exception failure) {
            diagnostics.add("JAVA_PARSE_FAILED: " + sourceName + ": " + failure.getMessage());
        }
        return new ParseOutcome(entities, diagnostics);
    }

    private static MappingReport correlate(List<EntityMapping> entities, List<String> diagnostics,
                                           int files, long bytes) {
        Map<String, List<EntityMapping>> bySimple = new LinkedHashMap<>();
        for (EntityMapping entity : entities)
            bySimple.computeIfAbsent(entity.simpleName(), ignored -> new ArrayList<>()).add(entity);
        List<RelationshipEdge> edges = new ArrayList<>();
        for (EntityMapping entity : entities) {
            for (RelationshipMapping relation : entity.relationships()) {
                String target = relation.targetEntity();
                if (target == null || target.isBlank()) {
                    diagnostics.add("UNRESOLVED_JPA_TARGET: " + entity.qualifiedName() + "." + relation.fieldName());
                    continue;
                }
                String resolved = entities.stream().anyMatch(candidate -> candidate.qualifiedName().equals(target))
                        ? target : null;
                if (resolved == null && !target.contains(".")) {
                    String samePackage = entity.packageName().isBlank() ? target : entity.packageName() + "." + target;
                    if (entities.stream().anyMatch(candidate -> candidate.qualifiedName().equals(samePackage)))
                        resolved = samePackage;
                }
                if (resolved == null && !target.contains(".")) {
                    List<String> explicitImports = entity.imports().stream()
                            .filter(name -> name.endsWith("." + target) && !name.endsWith(".*"))
                            .filter(name -> entities.stream().anyMatch(candidate -> candidate.qualifiedName().equals(name)))
                            .toList();
                    if (explicitImports.size() == 1) resolved = explicitImports.getFirst();
                    else if (explicitImports.size() > 1) {
                        diagnostics.add("AMBIGUOUS_JPA_TARGET: " + entity.qualifiedName() + "." + relation.fieldName() + " -> " + target);
                        continue;
                    }
                }
                if (resolved == null && bySimple.containsKey(target) && bySimple.get(target).size() == 1)
                    resolved = bySimple.get(target).getFirst().qualifiedName();
                if (resolved == null) {
                    diagnostics.add((bySimple.containsKey(target) ? "AMBIGUOUS_JPA_TARGET: " : "UNRESOLVED_JPA_TARGET: ")
                            + entity.qualifiedName() + "." + relation.fieldName() + " -> " + target);
                    continue;
                }
                edges.add(new RelationshipEdge(entity.qualifiedName(), resolved, relation.fieldName(),
                        relation.kind(), relation.joinColumns(), relation.sourceLocator(), FactStatus.OBSERVED));
            }
        }
        edges.sort(Comparator.comparing(RelationshipEdge::fromEntity)
                .thenComparing(RelationshipEdge::fieldName).thenComparing(RelationshipEdge::toEntity));
        entities.sort(Comparator.comparing(EntityMapping::qualifiedName));
        return new MappingReport(entities, edges, diagnostics,
                List.of("Only explicit jakarta.persistence/javax.persistence annotations are extracted.",
                        "No database connectivity, schema comparison, implicit naming strategy or runtime ORM metadata is used.",
                        "Unresolved or ambiguous target entities are diagnostics; no target is guessed.",
                        "Mapped column names are declarations, not proof that the physical database matches them."),
                files, bytes);
    }

    private static Map<String, AnnotationTree> annotations(ModifiersTree modifiers, Set<String> imports) {
        Map<String, AnnotationTree> result = new LinkedHashMap<>();
        for (AnnotationTree annotation : modifiers.getAnnotations()) {
            String name = annotationName(annotation);
            if (isJpa(name, imports)) result.put(name.substring(name.lastIndexOf('.') + 1), annotation);
        }
        return result;
    }

    private static boolean isJpa(String name, Set<String> imports) {
        if (name.startsWith("jakarta.persistence.") || name.startsWith("javax.persistence.")) return true;
        if (name.contains(".")) return false;
        return imports.contains("jakarta.persistence.*") || imports.contains("javax.persistence.*")
                || imports.contains("jakarta.persistence." + name) || imports.contains("javax.persistence." + name);
    }

    private static String annotationName(AnnotationTree annotation) {
        return annotation.getAnnotationType().toString();
    }

    private static Map<String, String> values(AnnotationTree annotation) {
        Map<String, String> values = new LinkedHashMap<>();
        if (annotation == null) return values;
        for (ExpressionTree argument : annotation.getArguments()) {
            if (argument instanceof AssignmentTree assignment)
                values.put(assignment.getVariable().toString(), literal(assignment.getExpression()));
            else values.put("value", literal(argument));
        }
        return values;
    }

    private static String literal(ExpressionTree expression) {
        String value = expression.toString();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
            return value.substring(1, value.length() - 1);
        return value;
    }

    private static ColumnMapping column(String field, AnnotationTree annotation) {
        Map<String, String> args = values(annotation);
        return new ColumnMapping(field, args.getOrDefault("name", ""), args.getOrDefault("referencedColumnName", ""),
                args.getOrDefault("nullable", ""), args.getOrDefault("unique", ""));
    }

    private static List<AnnotationTree> nestedAnnotations(AnnotationTree annotation) {
        List<AnnotationTree> result = new ArrayList<>();
        for (ExpressionTree argument : annotation.getArguments()) {
            ExpressionTree value = argument instanceof AssignmentTree assignment ? assignment.getExpression() : argument;
            if (value instanceof NewArrayTree array) {
                for (ExpressionTree item : array.getInitializers())
                    if (item instanceof AnnotationTree nested) result.add(nested);
            } else if (value instanceof AnnotationTree nested) result.add(nested);
        }
        return result;
    }

    private static String relationshipTarget(String type, String relationship) {
        String value = type.trim();
        if (relationship.equals("OneToMany") || relationship.equals("ManyToMany")) {
            int start = value.indexOf('<'), end = value.lastIndexOf('>');
            if (start >= 0 && end > start) value = value.substring(start + 1, end).trim();
        }
        value = value.replaceAll("<.*$", "").replace("[]", "").trim();
        if (value.startsWith("? extends ")) value = value.substring(10).trim();
        if (value.startsWith("? super ")) value = value.substring(8).trim();
        return value.isBlank() ? null : value;
    }

    public enum FactStatus { OBSERVED }
    public record ColumnMapping(String fieldName, String columnName, String referencedColumnName,
                                String nullable, String unique) {}
    public record RelationshipMapping(String fieldName, String kind, String targetEntity, String mappedBy,
                                      List<ColumnMapping> joinColumns, String sourceLocator, FactStatus status) {
        public RelationshipMapping { joinColumns = List.copyOf(joinColumns); }
    }
    public record EntityMapping(String qualifiedName, String simpleName, String packageName, List<String> imports,
                                String tableName, String schema, List<ColumnMapping> columns,
                                List<RelationshipMapping> relationships, String sourceLocator, FactStatus status) {
        public EntityMapping { imports = List.copyOf(imports); columns = List.copyOf(columns); relationships = List.copyOf(relationships); }
    }
    public record RelationshipEdge(String fromEntity, String toEntity, String fieldName, String kind,
                                    List<ColumnMapping> joinColumns, String sourceLocator, FactStatus status) {
        public RelationshipEdge { joinColumns = List.copyOf(joinColumns); }
    }
    public record MappingReport(List<EntityMapping> entities, List<RelationshipEdge> relationships,
                                List<String> diagnostics, List<String> limitations,
                                int filesScanned, long bytesRead) {
        public MappingReport {
            entities = List.copyOf(entities); relationships = List.copyOf(relationships);
            diagnostics = List.copyOf(diagnostics); limitations = List.copyOf(limitations);
        }
    }
    private record ParseOutcome(List<EntityMapping> entities, List<String> diagnostics) {}

    private static final class Source extends SimpleJavaFileObject {
        private final String text;
        private Source(String name, String text) {
            super(URI.create("string:///" + safe(name)), Kind.SOURCE);
            this.text = text;
        }
        private static String safe(String name) {
            String value = name.replace('\\', '/');
            int slash = value.lastIndexOf('/');
            value = slash >= 0 ? value.substring(slash + 1) : value;
            value = value.replaceAll("[^A-Za-z0-9._-]", "_");
            return value.endsWith(".java") ? value : value + ".java";
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
    }
}

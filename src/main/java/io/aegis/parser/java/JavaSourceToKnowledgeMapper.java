package io.aegis.parser.java;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps explicit Java declarations and import statements into the canonical project model. */
public final class JavaSourceToKnowledgeMapper {
    private static final Pattern TYPE_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");
    private static final Set<String> NON_REFERENCE_TYPES = Set.of(
            "void", "boolean", "byte", "short", "int", "long", "char", "float", "double", "var");

    public ProjectKnowledgeModel merge(ProjectKnowledgeModel project,
                                       List<JavaSourceAnalyzer.AnalysisResult> analyses) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(analyses, "analyses");

        Map<String, ProjectKnowledgeModel.ApplicationComponent> components = new LinkedHashMap<>();
        for (ProjectKnowledgeModel.ApplicationComponent component : project.components())
            components.put(component.id(), component);

        Map<String, ProjectKnowledgeModel.Dependency> dependencies = new LinkedHashMap<>();
        for (ProjectKnowledgeModel.Dependency dependency : project.dependencies())
            dependencies.put(key(dependency.from(), dependency.to(), dependency.kind()), dependency);

        for (JavaSourceAnalyzer.AnalysisResult analysis : analyses) {
            for (JavaSourceAnalyzer.SourceFile file : analysis.files()) {
                for (JavaSourceAnalyzer.TypeDeclaration type : file.types()) {
                    components.putIfAbsent(type.qualifiedName(),
                            new ProjectKnowledgeModel.ApplicationComponent(type.qualifiedName(),
                                    type.qualifiedName(), "JAVA_" + type.kind().toUpperCase(Locale.ROOT),
                                    file.sourceName() + ":" + type.line(),
                                    ProjectKnowledgeModel.FactStatus.OBSERVED));
                }
            }
        }

        // Resolve only explicit import names against the complete set of discovered project types.
        // This is deterministic name matching, not compiler symbol resolution.
        for (JavaSourceAnalyzer.AnalysisResult analysis : analyses) {
            for (JavaSourceAnalyzer.SourceFile file : analysis.files()) {
                for (JavaSourceAnalyzer.TypeDeclaration type : file.types()) {
                    for (JavaSourceAnalyzer.ImportDeclaration imported : file.imports()) {
                        List<String> internalTargets = resolveInternalTargets(imported, components.keySet());
                        if (!internalTargets.isEmpty()) {
                            for (String target : internalTargets) {
                                ProjectKnowledgeModel.Dependency dependency = new ProjectKnowledgeModel.Dependency(
                                        type.qualifiedName(), target, "JAVA_INTERNAL_IMPORT",
                                        ProjectKnowledgeModel.FactStatus.OBSERVED, List.of());
                                dependencies.putIfAbsent(key(dependency.from(), dependency.to(), dependency.kind()), dependency);
                            }
                        } else {
                            String kind = imported.isStatic() ? "JAVA_STATIC_IMPORT"
                                    : imported.qualifiedName().endsWith(".*") ? "JAVA_WILDCARD_IMPORT"
                                    : "JAVA_IMPORT";
                            ProjectKnowledgeModel.Dependency dependency = new ProjectKnowledgeModel.Dependency(
                                    type.qualifiedName(), imported.qualifiedName(), kind,
                                    ProjectKnowledgeModel.FactStatus.OBSERVED, List.of());
                            dependencies.putIfAbsent(key(dependency.from(), dependency.to(), dependency.kind()), dependency);
                        }
                    }
                    for (String reference : referencedTypes(file, type, components.keySet())) {
                        if (reference.equals(type.qualifiedName())) continue;
                        ProjectKnowledgeModel.Dependency dependency = new ProjectKnowledgeModel.Dependency(
                                type.qualifiedName(), reference, "JAVA_TYPE_REFERENCE",
                                ProjectKnowledgeModel.FactStatus.OBSERVED, List.of());
                        dependencies.putIfAbsent(key(dependency.from(), dependency.to(), dependency.kind()), dependency);
                    }
                }
            }
        }

        return new ProjectKnowledgeModel(project.project(), project.artifacts(), project.technologies(),
                new ArrayList<>(components.values()), project.dataStores(), project.logicalEntities(),
                project.apiEndpoints(), new ArrayList<>(dependencies.values()), project.businessRules(),
                project.analyzedAt(), project.modelVersion());
    }

    private static List<String> referencedTypes(JavaSourceAnalyzer.SourceFile file,
                                                 JavaSourceAnalyzer.TypeDeclaration type,
                                                 Set<String> componentIds) {
        List<String> expressions = new ArrayList<>();
        if (type.extendsType() != null) expressions.add(type.extendsType());
        expressions.addAll(type.implementsTypes());
        type.fields().forEach(field -> expressions.add(field.type()));
        type.methods().forEach(method -> {
            expressions.add(method.parameterTypes());
            expressions.add(method.returnType());
        });
        Set<String> targets = new LinkedHashSet<>();
        for (String expression : expressions) {
            if (expression == null) continue;
            Matcher matcher = TYPE_TOKEN.matcher(expression);
            while (matcher.find()) {
                String resolved = resolveTypeToken(matcher.group(), file, componentIds);
                if (resolved != null && !resolved.equals(type.qualifiedName())) targets.add(resolved);
            }
        }
        return targets.stream().sorted().toList();
    }

    private static String resolveTypeToken(String token, JavaSourceAnalyzer.SourceFile file,
                                           Set<String> componentIds) {
        if (NON_REFERENCE_TYPES.contains(token)) return null;
        if (componentIds.contains(token)) return token;
        String packageName = file.packageName();
        if (packageName != null && !packageName.isBlank()) {
            String samePackage = packageName + "." + token;
            if (componentIds.contains(samePackage)) return samePackage;
        }
        for (JavaSourceAnalyzer.ImportDeclaration imported : file.imports()) {
            if (imported.isStatic()) continue;
            String name = imported.qualifiedName();
            if (name.endsWith(".*")) {
                String candidate = name.substring(0, name.length() - 1) + token;
                if (componentIds.contains(candidate)) return candidate;
            } else if (name.substring(name.lastIndexOf('.') + 1).equals(token)
                    && componentIds.contains(name)) return name;
        }
        List<String> matches = componentIds.stream()
                .filter(id -> id.endsWith("." + token)).sorted().toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<String> resolveInternalTargets(JavaSourceAnalyzer.ImportDeclaration imported,
                                                       java.util.Set<String> componentIds) {
        String name = imported.qualifiedName();
        if (!imported.isStatic() && name.endsWith(".*")) {
            String packagePrefix = name.substring(0, name.length() - 2) + ".";
            return componentIds.stream().filter(id -> id.startsWith(packagePrefix)).sorted().toList();
        }
        if (!imported.isStatic())
            return componentIds.contains(name) ? List.of(name) : List.of();

        // A static member import names its declaring type followed by a member; resolve
        // the longest prefix that is a discovered project type.
        int separator = name.lastIndexOf('.');
        while (separator > 0) {
            String owner = name.substring(0, separator);
            if (componentIds.contains(owner)) return List.of(owner);
            separator = name.lastIndexOf('.', separator - 1);
        }
        return List.of();
    }

    private static String key(String from, String to, String kind) {
        return from + "|" + kind + "|" + to;
    }
}

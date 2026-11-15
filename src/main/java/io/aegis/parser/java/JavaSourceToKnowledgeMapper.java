package io.aegis.parser.java;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps explicit Java declarations and import statements into the canonical project model. */
public final class JavaSourceToKnowledgeMapper {

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
                                    type.qualifiedName(), "JAVA_" + type.kind().toUpperCase(),
                                    file.sourceName() + ":" + type.line(),
                                    ProjectKnowledgeModel.FactStatus.OBSERVED));

                    for (JavaSourceAnalyzer.ImportDeclaration imported : file.imports()) {
                        String kind = imported.isStatic() ? "JAVA_STATIC_IMPORT"
                                : imported.qualifiedName().endsWith(".*") ? "JAVA_WILDCARD_IMPORT"
                                : "JAVA_IMPORT";
                        ProjectKnowledgeModel.Dependency dependency = new ProjectKnowledgeModel.Dependency(
                                type.qualifiedName(), imported.qualifiedName(), kind,
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

    private static String key(String from, String to, String kind) {
        return from + "|" + kind + "|" + to;
    }
}

package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative logical-model inference. Every inferred fact is explicitly marked INFERRED. */
public final class LogicalModelInferer {
    private static final Pattern VALIDATION = Pattern.compile("@(NotNull|NotBlank|NotEmpty|Email|Min|Max|Size)\\b(?:\\(([^)]*)\\))?");
    private static final Pattern THROW_RULE = Pattern.compile("if\\s*\\(([^)]{1,240})\\)\\s*\\{?\\s*throw\\s+new\\s+([A-Za-z0-9_.]+)");

    public InferenceReport infer(ProjectKnowledgeModel project, List<String> javaSources) {
        List<ProjectKnowledgeModel.LogicalEntity> entities = new ArrayList<>(project.logicalEntities());
        List<ProjectKnowledgeModel.BusinessRule> rules = new ArrayList<>(project.businessRules());
        for (ProjectKnowledgeModel.DataStore store : project.dataStores()) {
            for (ProjectKnowledgeModel.DbSchema schema : store.schemas()) {
                for (ProjectKnowledgeModel.DbTable table : schema.tables()) {
                    String id = normalize(store.id() + "_" + schema.name() + "_" + table.name());
                    if (entities.stream().noneMatch(e -> e.id().equals(id))) {
                        List<ProjectKnowledgeModel.LogicalAttribute> attrs = table.columns().stream()
                                .map(c -> new ProjectKnowledgeModel.LogicalAttribute(c.name(),
                                        businessType(c.dataType()), !c.nullable(), c.defaultExpression(),
                                        ProjectKnowledgeModel.FactStatus.INFERRED)).toList();
                        List<ProjectKnowledgeModel.LogicalRelationship> rels = table.constraints().stream()
                                .filter(c -> c.type() == ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY && c.referencedTable() != null)
                                .map(c -> new ProjectKnowledgeModel.LogicalRelationship(
                                        id + "_fk_" + normalize(c.name() == null ? c.referencedTable() : c.name()),
                                        id, normalize(c.referencedTable()), ProjectKnowledgeModel.RelationshipKind.MANY_TO_ONE,
                                        ProjectKnowledgeModel.FactStatus.INFERRED, List.of())).toList();
                        entities.add(new ProjectKnowledgeModel.LogicalEntity(id, table.name(), attrs, rels,
                                ProjectKnowledgeModel.FactStatus.INFERRED));
                    }
                }
            }
        }
        int before = rules.size();
        if (javaSources != null) {
            int sourceIndex = 0;
            for (String source : javaSources) {
                if (source == null) continue;
                Matcher validation = VALIDATION.matcher(source);
                while (validation.find()) {
                    String statement = "Validation constraint @" + validation.group(1)
                            + (validation.group(2) == null ? "" : "(" + validation.group(2).trim() + ")");
                    addRule(rules, "java-validation-" + sourceIndex + "-" + validation.start(),
                            statement, "java-source:" + sourceIndex);
                }
                Matcher conditional = THROW_RULE.matcher(source);
                while (conditional.find()) {
                    addRule(rules, "java-guard-" + sourceIndex + "-" + conditional.start(),
                            "When " + conditional.group(1).trim() + ", throw " + conditional.group(2),
                            "java-source:" + sourceIndex);
                }
                sourceIndex++;
            }
        }
        return new InferenceReport(new ProjectKnowledgeModel(project.project(), project.artifacts(),
                project.technologies(), project.components(), project.dataStores(), entities,
                project.apiEndpoints(), project.dependencies(), rules, project.analyzedAt(), project.modelVersion()),
                entities.size() - project.logicalEntities().size(), rules.size() - before,
                List.of("Inferred entities and rules require human review; naming and cardinality are heuristic."));
    }

    private static void addRule(List<ProjectKnowledgeModel.BusinessRule> rules, String id, String text, String source) {
        if (rules.stream().noneMatch(rule -> rule.id().equals(id)))
            rules.add(new ProjectKnowledgeModel.BusinessRule(id, text, source, ProjectKnowledgeModel.FactStatus.INFERRED));
    }
    private static String businessType(String sql) {
        String type = sql.toLowerCase(Locale.ROOT);
        if (type.contains("int") || type.contains("number")) return "NUMBER";
        if (type.contains("date") || type.contains("time")) return "DATETIME";
        if (type.contains("bool")) return "BOOLEAN";
        if (type.contains("char") || type.contains("text") || type.contains("clob")) return "STRING";
        if (type.contains("decimal") || type.contains("numeric") || type.contains("float")) return "DECIMAL";
        return "UNKNOWN";
    }
    private static String normalize(String value) { return value.replaceAll("[^A-Za-z0-9]+", "_").toLowerCase(Locale.ROOT); }

    public record InferenceReport(ProjectKnowledgeModel knowledgeModel, int entitiesAdded,
                                  int businessRulesAdded, List<String> reviewNotes) {
        public InferenceReport { reviewNotes=List.copyOf(reviewNotes); }
    }
}

package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares explicit JPA table/column/join declarations with discovered physical DDL metadata.
 * This is a static reconciliation aid, not proof of provider runtime behavior or database connectivity.
 */
public final class JpaSchemaReconciliationAnalyzer {

    public ReconciliationReport reconcile(ProjectKnowledgeModel model,
                                           JpaMappingAnalyzer.MappingReport mappings) {
        if (model == null) throw new IllegalArgumentException("project knowledge model is required");
        if (mappings == null) throw new IllegalArgumentException("JPA mapping report is required");

        List<Finding> findings = new ArrayList<>();
        if (model.dataStores().isEmpty()) {
            for (var entity : mappings.entities())
                findings.add(new Finding("PHYSICAL_SCHEMA_NOT_SUPPLIED", Severity.INFO, Status.NOT_CHECKED,
                        entity.qualifiedName(), entity.tableName(), null,
                        "No physical data store was supplied; table and column compatibility was not evaluated.",
                        entity.sourceLocator(), null));
            return report(findings);
        }

        for (JpaMappingAnalyzer.EntityMapping entity : mappings.entities()) {
            List<TableMatch> matches = findTables(model, entity);
            if (matches.isEmpty()) {
                findings.add(new Finding("JPA_ENTITY_TABLE_NOT_FOUND", Severity.WARNING, Status.MISMATCH,
                        entity.qualifiedName(), entity.tableName(), null,
                        "No discovered physical table matches the JPA table declaration.",
                        entity.sourceLocator(), null));
                continue;
            }
            if (matches.size() > 1) {
                findings.add(new Finding("JPA_ENTITY_TABLE_AMBIGUOUS", Severity.WARNING, Status.AMBIGUOUS,
                        entity.qualifiedName(), entity.tableName(), null,
                        "More than one physical table matches this entity; schema/store selection is required.",
                        entity.sourceLocator(), null));
                continue;
            }

            TableMatch match = matches.getFirst();
            findings.add(new Finding("JPA_ENTITY_TABLE_MATCHED", Severity.INFO, Status.MATCH,
                    entity.qualifiedName(), match.table().name(), null,
                    "JPA entity table maps to a discovered physical table.",
                    entity.sourceLocator(), match.table().sourceLocator()));

            for (JpaMappingAnalyzer.ColumnMapping column : entity.columns()) {
                if (blank(column.columnName())) continue; // Provider naming strategy/default field mapping is not guessed.
                boolean exists = match.table().columns().stream()
                        .anyMatch(candidate -> same(candidate.name(), column.columnName()));
                findings.add(new Finding(exists ? "JPA_COLUMN_MATCHED" : "JPA_COLUMN_NOT_FOUND",
                        exists ? Severity.INFO : Severity.WARNING,
                        exists ? Status.MATCH : Status.MISMATCH,
                        entity.qualifiedName(), match.table().name(), column.fieldName(),
                        exists ? "Explicit JPA column exists in the physical table."
                                : "Explicit JPA column is absent from the physical table.",
                        entity.sourceLocator(), match.table().sourceLocator()));
            }
        }

        for (JpaMappingAnalyzer.RelationshipEdge relation : mappings.relationships()) {
            JpaMappingAnalyzer.EntityMapping sourceEntity = entity(mappings, relation.fromEntity());
            JpaMappingAnalyzer.EntityMapping targetEntity = entity(mappings, relation.toEntity());
            if (sourceEntity == null || targetEntity == null) continue;
            List<TableMatch> sourceTables = findTables(model, sourceEntity);
            List<TableMatch> targetTables = findTables(model, targetEntity);
            if (sourceTables.size() != 1 || targetTables.size() != 1) continue;

            if (relation.joinColumns().isEmpty()
                    || relation.joinColumns().stream().anyMatch(join ->
                    blank(join.columnName()) || blank(join.referencedColumnName()))) {
                findings.add(new Finding("JPA_JOIN_MAPPING_NOT_EXPLICIT", Severity.INFO, Status.NOT_CHECKED,
                        relation.fromEntity(), sourceTables.getFirst().table().name(), relation.fieldName(),
                        "Join column or referenced column is implicit; physical FK equivalence was not guessed.",
                        relation.sourceLocator(), null));
                continue;
            }

            List<String> sourceColumns = relation.joinColumns().stream()
                    .map(JpaMappingAnalyzer.ColumnMapping::columnName).toList();
            List<String> targetColumns = relation.joinColumns().stream()
                    .map(JpaMappingAnalyzer.ColumnMapping::referencedColumnName).toList();
            boolean fkMatches = sourceTables.getFirst().table().constraints().stream()
                    .filter(constraint -> constraint.type() == ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY)
                    .anyMatch(constraint -> sameList(constraint.columns(), sourceColumns)
                            && same(lastName(constraint.referencedTable()), targetTables.getFirst().table().name())
                            && sameList(constraint.referencedColumns(), targetColumns));
            findings.add(new Finding(fkMatches ? "JPA_RELATIONSHIP_FK_MATCHED" : "JPA_RELATIONSHIP_FK_NOT_FOUND",
                    fkMatches ? Severity.INFO : Severity.WARNING,
                    fkMatches ? Status.MATCH : Status.MISMATCH,
                    relation.fromEntity(), sourceTables.getFirst().table().name(), relation.fieldName(),
                    fkMatches ? "Explicit JPA join columns match a discovered physical foreign key."
                            : "No physical foreign key matches the explicit JPA source/target column mapping.",
                    relation.sourceLocator(), sourceTables.getFirst().table().sourceLocator()));
        }

        return report(findings);
    }

    private static List<TableMatch> findTables(ProjectKnowledgeModel model,
                                                JpaMappingAnalyzer.EntityMapping entity) {
        List<TableMatch> matches = new ArrayList<>();
        for (ProjectKnowledgeModel.DataStore store : model.dataStores()) {
            for (ProjectKnowledgeModel.DbSchema schema : store.schemas()) {
                if (!blank(entity.schema()) && !same(entity.schema(), schema.name())) continue;
                for (ProjectKnowledgeModel.DbTable table : schema.tables()) {
                    if (same(entity.tableName(), table.name()))
                        matches.add(new TableMatch(store, schema, table));
                }
            }
        }
        return matches;
    }

    private static JpaMappingAnalyzer.EntityMapping entity(JpaMappingAnalyzer.MappingReport report, String name) {
        return report.entities().stream().filter(candidate -> candidate.qualifiedName().equals(name))
                .findFirst().orElse(null);
    }

    private static boolean sameList(List<String> left, List<String> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++)
            if (!same(left.get(i), right.get(i))) return false;
        return true;
    }

    private static String lastName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static boolean same(String left, String right) {
        return left != null && right != null
                && left.trim().toUpperCase(Locale.ROOT).equals(right.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static ReconciliationReport report(List<Finding> findings) {
        long matched = findings.stream().filter(f -> f.status() == Status.MATCH).count();
        long mismatched = findings.stream().filter(f -> f.status() == Status.MISMATCH).count();
        long ambiguous = findings.stream().filter(f -> f.status() == Status.AMBIGUOUS).count();
        long notChecked = findings.stream().filter(f -> f.status() == Status.NOT_CHECKED).count();
        return new ReconciliationReport(findings, new Counts((int) matched, (int) mismatched,
                (int) ambiguous, (int) notChecked),
                List.of("Only explicit JPA names and discovered DDL metadata are compared.",
                        "Implicit naming strategies, ORM provider defaults and runtime schema state are not evaluated.",
                        "A match is static metadata agreement, not proof of runtime compatibility."));
    }

    public enum Severity { INFO, WARNING, ERROR }
    public enum Status { MATCH, MISMATCH, AMBIGUOUS, NOT_CHECKED }
    public record Finding(String code, Severity severity, Status status, String entity,
                          String table, String field, String message,
                          String sourceLocator, String physicalLocator) {}
    public record Counts(int matched, int mismatched, int ambiguous, int notChecked) {}
    public record ReconciliationReport(List<Finding> findings, Counts counts, List<String> limitations) {
        public ReconciliationReport { findings = List.copyOf(findings); limitations = List.copyOf(limitations); }
    }
    private record TableMatch(ProjectKnowledgeModel.DataStore store,
                              ProjectKnowledgeModel.DbSchema schema,
                              ProjectKnowledgeModel.DbTable table) {}
}

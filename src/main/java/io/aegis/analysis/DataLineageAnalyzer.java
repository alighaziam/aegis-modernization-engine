package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts physical, declared foreign-key lineage from the UPKM.
 * It does not infer joins from naming conventions or inspect runtime data.
 */
public final class DataLineageAnalyzer {

    public LineageReport analyze(ProjectKnowledgeModel project) {
        if (project == null) throw new IllegalArgumentException("project is required");

        List<LineageEdge> edges = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (ProjectKnowledgeModel.DataStore store : project.dataStores()) {
            for (ProjectKnowledgeModel.DbSchema schema : store.schemas()) {
                for (ProjectKnowledgeModel.DbTable table : schema.tables()) {
                    String sourceTable = qualify(store.id(), schema.name(), table.name());
                    for (ProjectKnowledgeModel.DbConstraint constraint : table.constraints()) {
                        if (constraint.type() != ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY) continue;
                        if (constraint.referencedTable() == null || constraint.referencedTable().isBlank()) {
                            diagnostics.add("INCOMPLETE_FOREIGN_KEY: " + sourceTable + " constraint "
                                    + safeName(constraint.name()) + " has no referenced table.");
                            continue;
                        }

                        List<String> sourceColumns = constraint.columns();
                        List<String> targetColumns = constraint.referencedColumns();
                        if (sourceColumns.isEmpty() || targetColumns.isEmpty()) {
                            diagnostics.add("INCOMPLETE_FOREIGN_KEY_COLUMNS: " + sourceTable + " constraint "
                                    + safeName(constraint.name()) + " does not declare both column lists.");
                        } else if (sourceColumns.size() != targetColumns.size()) {
                            diagnostics.add("FOREIGN_KEY_COLUMN_ARITY_MISMATCH: " + sourceTable + " constraint "
                                    + safeName(constraint.name()) + " has " + sourceColumns.size()
                                    + " source columns and " + targetColumns.size() + " referenced columns.");
                        }

                        String targetTable = qualifyReference(store.id(), schema.name(), constraint.referencedTable());
                        edges.add(new LineageEdge(sourceTable, targetTable, sourceColumns, targetColumns,
                                constraint.name(), table.sourceLocator(), ProjectKnowledgeModel.FactStatus.OBSERVED));
                    }
                }
            }
        }

        edges.sort((left, right) -> {
            int source = left.sourceTable().compareTo(right.sourceTable());
            if (source != 0) return source;
            int target = left.targetTable().compareTo(right.targetTable());
            if (target != 0) return target;
            return safeName(left.constraintName()).compareTo(safeName(right.constraintName()));
        });
        return new LineageReport(edges, diagnostics,
                List.of("Only declared physical foreign keys are reported.",
                        "No joins are inferred from column names, ORM conventions, SQL queries or runtime observations.",
                        "Cross-database and cross-schema references are retained as declared names and are not connectivity-validated."));
    }

    private static String qualify(String storeId, String schema, String table) {
        return storeId + "." + schema + "." + table;
    }

    private static String qualifyReference(String storeId, String schema, String table) {
        long separators = table.chars().filter(character -> character == '.').count();
        if (separators >= 2) return table;
        return separators == 1 ? storeId + "." + table : storeId + "." + schema + "." + table;
    }

    private static String safeName(String value) {
        return value == null || value.isBlank() ? "<unnamed>" : value;
    }

    public record LineageEdge(String sourceTable, String targetTable,
                              List<String> sourceColumns, List<String> targetColumns,
                              String constraintName, String sourceLocator,
                              ProjectKnowledgeModel.FactStatus status) {
        public LineageEdge {
            sourceColumns = List.copyOf(sourceColumns);
            targetColumns = List.copyOf(targetColumns);
        }
    }

    public record LineageReport(List<LineageEdge> edges, List<String> diagnostics,
                                List<String> limitations) {
        public LineageReport {
            edges = List.copyOf(edges);
            diagnostics = List.copyOf(diagnostics);
            limitations = List.copyOf(limitations);
        }
    }
}

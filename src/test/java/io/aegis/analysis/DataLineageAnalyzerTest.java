package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataLineageAnalyzerTest {

    @Test
    void extractsDeclaredSingleAndCompositeForeignKeyLineageDeterministically() {
        var customer = new ProjectKnowledgeModel.DbTable("CUSTOMER",
                List.of(new ProjectKnowledgeModel.DbColumn("ID", "NUMBER", null, null, null,
                        false, null, "schema.sql:1")),
                List.of(), List.of(), "schema.sql:1");
        var order = new ProjectKnowledgeModel.DbTable("ORDERS",
                List.of(
                        new ProjectKnowledgeModel.DbColumn("CUSTOMER_ID", "NUMBER", null, null, null,
                                false, null, "schema.sql:10"),
                        new ProjectKnowledgeModel.DbColumn("TENANT_ID", "NUMBER", null, null, null,
                                false, null, "schema.sql:11")),
                List.of(new ProjectKnowledgeModel.DbConstraint("FK_ORDERS_CUSTOMER",
                                ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                                List.of("CUSTOMER_ID", "TENANT_ID"), "CUSTOMER",
                                List.of("ID", "TENANT_ID"), "schema.sql:12")),
                List.of(), "schema.sql:10");
        var project = projectWithTables(customer, order);

        var report = new DataLineageAnalyzer().analyze(project);

        assertEquals(1, report.edges().size());
        var edge = report.edges().getFirst();
        assertEquals("legacy.SALES.ORDERS", edge.sourceTable());
        assertEquals("legacy.SALES.CUSTOMER", edge.targetTable());
        assertEquals(List.of("CUSTOMER_ID", "TENANT_ID"), edge.sourceColumns());
        assertEquals(List.of("ID", "TENANT_ID"), edge.targetColumns());
        assertEquals("FK_ORDERS_CUSTOMER", edge.constraintName());
        assertEquals(ProjectKnowledgeModel.FactStatus.OBSERVED, edge.status());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void preservesSchemaAndDatabaseQualifiedForeignKeyTargets() {
        var source = new ProjectKnowledgeModel.DbTable("ORDERS", List.of(), List.of(
                new ProjectKnowledgeModel.DbConstraint("FK_SCHEMA",
                        ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                        List.of("ACCOUNT_ID"), "ARCHIVE.ACCOUNT", List.of("ID"), "schema.sql:2"),
                new ProjectKnowledgeModel.DbConstraint("FK_DATABASE",
                        ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                        List.of("CUSTOMER_ID"), "OTHER_DB.CRM.CUSTOMER", List.of("ID"), "schema.sql:3")
        ), List.of(), "schema.sql:1");

        var report = new DataLineageAnalyzer().analyze(projectWithTables(source));

        assertEquals(List.of("OTHER_DB.CRM.CUSTOMER", "legacy.ARCHIVE.ACCOUNT"),
                report.edges().stream().map(DataLineageAnalyzer.LineageEdge::targetTable).toList());
    }

    @Test
    void reportsIncompleteForeignKeyMetadataWithoutInventingColumnMappings() {
        var table = new ProjectKnowledgeModel.DbTable("INVOICE",
                List.of(), List.of(
                new ProjectKnowledgeModel.DbConstraint("FK_INVOICE_ACCOUNT",
                        ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                        List.of("ACCOUNT_ID"), "ACCOUNT", List.of(), "invoice.sql:4"),
                new ProjectKnowledgeModel.DbConstraint("FK_INVOICE_BAD",
                        ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                        List.of("A", "B"), "OTHER", List.of("ID"), "invoice.sql:8"),
                new ProjectKnowledgeModel.DbConstraint("FK_INVOICE_MISSING",
                        ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                        List.of("X"), null, List.of("ID"), "invoice.sql:12")),
                List.of(), "invoice.sql:1");
        var report = new DataLineageAnalyzer().analyze(projectWithTables(table));

        assertEquals(2, report.edges().size());
        assertEquals(List.of(), report.edges().getFirst().targetColumns());
        assertEquals(3, report.diagnostics().size());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("INCOMPLETE_FOREIGN_KEY:")));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("INCOMPLETE_FOREIGN_KEY_COLUMNS:")));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("FOREIGN_KEY_COLUMN_ARITY_MISMATCH:")));
    }

    private static ProjectKnowledgeModel projectWithTables(ProjectKnowledgeModel.DbTable... tables) {
        var store = new ProjectKnowledgeModel.DataStore("legacy", "ORACLE", "19c",
                List.of(new ProjectKnowledgeModel.DbSchema("SALES", List.of(tables))));
        return new ProjectKnowledgeModel(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(), List.of(), List.of(store), List.of(), List.of(),
                List.of(), List.of(), null, "1.0");
    }
}

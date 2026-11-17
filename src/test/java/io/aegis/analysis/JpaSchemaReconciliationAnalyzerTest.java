package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JpaSchemaReconciliationAnalyzerTest {

    @Test
    void confirmsEntityColumnAndRelationshipAgainstPhysicalForeignKey() {
        var model = model(
                table("CUSTOMER", List.of(column("ID")), List.of()),
                table("ORDERS", List.of(column("CUSTOMER_ID")),
                        List.of(new ProjectKnowledgeModel.DbConstraint("FK_ORDERS_CUSTOMER",
                                ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                                List.of("CUSTOMER_ID"), "CUSTOMER", List.of("ID"), "schema.sql:9"))));
        var mappings = mappings(false);

        var report = new JpaSchemaReconciliationAnalyzer().reconcile(model, mappings);

        assertEquals(5, report.counts().matched());
        assertEquals(0, report.counts().mismatched());
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("JPA_RELATIONSHIP_FK_MATCHED")));
    }

    @Test
    void reportsMissingExplicitColumnAndForeignKeyWithoutGuessing() {
        var model = model(
                table("CUSTOMER", List.of(column("ID")), List.of()),
                table("ORDERS", List.of(), List.of()));
        var report = new JpaSchemaReconciliationAnalyzer().reconcile(model, mappings(false));

        assertEquals(2, report.counts().mismatched());
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("JPA_COLUMN_NOT_FOUND")));
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("JPA_RELATIONSHIP_FK_NOT_FOUND")));
    }

    @Test
    void reportsNotCheckedWhenNoPhysicalDatabaseWasProvided() {
        var model = new ProjectKnowledgeModel(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "1.0");
        var report = new JpaSchemaReconciliationAnalyzer().reconcile(model, mappings(false));

        assertEquals(2, report.counts().notChecked());
        assertEquals(0, report.counts().mismatched());
    }

    @Test
    void reportsAmbiguousTableWhenSameNameExistsInMultipleSchemas() {
        var first = new ProjectKnowledgeModel.DataStore("db1", "ORACLE", "19c",
                List.of(new ProjectKnowledgeModel.DbSchema("SALES", List.of(table("ORDERS", List.of(), List.of())))));
        var second = new ProjectKnowledgeModel.DataStore("db2", "POSTGRESQL", "16",
                List.of(new ProjectKnowledgeModel.DbSchema("PUBLIC", List.of(table("ORDERS", List.of(), List.of())))));
        var model = new ProjectKnowledgeModel(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(), List.of(), List.of(first, second), List.of(), List.of(), List.of(), List.of(), null, "1.0");
        var report = new JpaSchemaReconciliationAnalyzer().reconcile(model, mappings(false));

        assertTrue(report.findings().stream().anyMatch(f ->
                f.code().equals("JPA_ENTITY_TABLE_AMBIGUOUS") && f.status() == JpaSchemaReconciliationAnalyzer.Status.AMBIGUOUS));
    }

    private static JpaMappingAnalyzer.MappingReport mappings(boolean unused) {
        var customer = new JpaMappingAnalyzer.EntityMapping("com.acme.Customer", "Customer", "com.acme", List.of(),
                "CUSTOMER", "SALES", List.of(new JpaMappingAnalyzer.ColumnMapping("id", "ID", "", "false", "")),
                List.of(), "Customer.java", JpaMappingAnalyzer.FactStatus.OBSERVED);
        var order = new JpaMappingAnalyzer.EntityMapping("com.acme.Order", "Order", "com.acme", List.of(),
                "ORDERS", "SALES",
                List.of(new JpaMappingAnalyzer.ColumnMapping("customer", "CUSTOMER_ID", "ID", "", "")),
                List.of(), "Order.java", JpaMappingAnalyzer.FactStatus.OBSERVED);
        var edge = new JpaMappingAnalyzer.RelationshipEdge("com.acme.Order", "com.acme.Customer",
                "customer", "ManyToOne",
                List.of(new JpaMappingAnalyzer.ColumnMapping("customer", "CUSTOMER_ID", "ID", "", "")),
                "Order.java", JpaMappingAnalyzer.FactStatus.OBSERVED);
        return new JpaMappingAnalyzer.MappingReport(List.of(customer, order), List.of(edge),
                List.of(), List.of(), 2, 100);
    }

    private static ProjectKnowledgeModel model(ProjectKnowledgeModel.DbTable customer,
                                                ProjectKnowledgeModel.DbTable order) {
        var store = new ProjectKnowledgeModel.DataStore("legacy", "ORACLE", "19c",
                List.of(new ProjectKnowledgeModel.DbSchema("SALES", List.of(customer, order))));
        return new ProjectKnowledgeModel(new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(), List.of(), List.of(store), List.of(), List.of(), List.of(), List.of(), null, "1.0");
    }

    private static ProjectKnowledgeModel.DbTable table(String name, List<ProjectKnowledgeModel.DbColumn> columns,
                                                        List<ProjectKnowledgeModel.DbConstraint> constraints) {
        return new ProjectKnowledgeModel.DbTable(name, columns, constraints, List.of(), "schema.sql:1");
    }

    private static ProjectKnowledgeModel.DbColumn column(String name) {
        return new ProjectKnowledgeModel.DbColumn(name, "NUMBER", null, null, null, false, null, "schema.sql:2");
    }
}

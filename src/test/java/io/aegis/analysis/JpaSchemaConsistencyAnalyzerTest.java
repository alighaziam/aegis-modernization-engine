package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JpaSchemaConsistencyAnalyzerTest {
    @Test
    void matchesExplicitJpaTablesColumnsAndOwningRelationshipsToPhysicalDdl() {
        var model = physicalModel(true);
        var jpa = mappingReport("ORDERS", "CUSTOMERS", "CUSTOMER_ID", "ID");
        var lineage = new DataLineageAnalyzer().analyze(model);

        var report = new JpaSchemaConsistencyAnalyzer().analyze(model, jpa, lineage);

        assertTrue(report.available());
        assertEquals(2, report.matchedEntities());
        assertEquals(0, report.unmatchedEntities());
        assertEquals(3, report.checkedColumns());
        assertEquals(1, report.matchedRelationships());
        assertTrue(report.findings().isEmpty());
    }

    @Test
    void reportsMissingMappedColumnsAndJpaRelationshipsWithoutPhysicalForeignKeys() {
        var model = physicalModel(false);
        var jpa = mappingReport("ORDERS", "CUSTOMERS", "WRONG_CUSTOMER_ID", "ID");
        var lineage = new DataLineageAnalyzer().analyze(model);

        var report = new JpaSchemaConsistencyAnalyzer().analyze(model, jpa, lineage);

        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("JPA_COLUMN_NOT_FOUND")
                && f.severity().equals("ERROR")));
        assertTrue(report.findings().stream().anyMatch(f ->
                f.code().equals("JPA_RELATIONSHIP_WITHOUT_PHYSICAL_FK")));
        assertEquals(1, report.errorCount());
        assertEquals(1, report.warningCount());
    }

    @Test
    void marksConsistencyUnavailableWhenNoPhysicalSchemaWasSupplied() {
        var model = ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null));
        var report = new JpaSchemaConsistencyAnalyzer().analyze(model, mappingReport(
                "ORDERS", "CUSTOMERS", "CUSTOMER_ID", "ID"), new DataLineageAnalyzer().analyze(model));
        assertFalse(report.available());
        assertTrue(report.findings().isEmpty());
    }

    private static ProjectKnowledgeModel physicalModel(boolean withForeignKey) {
        var customers = new ProjectKnowledgeModel.DbTable("customers", List.of(
                new ProjectKnowledgeModel.DbColumn("id", "BIGINT", null, null, null, false, null, "schema.sql:1")),
                List.of(), List.of(), "schema.sql:1");
        var constraints = withForeignKey ? List.of(new ProjectKnowledgeModel.DbConstraint(
                "fk_orders_customer", ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                List.of("customer_id"), "customers", List.of("id"), "schema.sql:8")) : List.of();
        var orders = new ProjectKnowledgeModel.DbTable("orders", List.of(
                new ProjectKnowledgeModel.DbColumn("id", "BIGINT", null, null, null, false, null, "schema.sql:4"),
                new ProjectKnowledgeModel.DbColumn("customer_id", "BIGINT", null, null, null, false, null, "schema.sql:5")),
                constraints, List.of(), "schema.sql:4");
        var store = new ProjectKnowledgeModel.DataStore("db", "POSTGRESQL", "16",
                List.of(new ProjectKnowledgeModel.DbSchema("public", List.of(customers, orders))));
        return new ProjectKnowledgeModel(new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(), List.of(), List.of(store), List.of(), List.of(), List.of(), List.of(),
                Instant.parse("2026-11-17T00:00:00Z"), "1.0");
    }

    private static JpaMappingAnalyzer.MappingReport mappingReport(String orderTable, String customerTable,
                                                                  String joinColumn, String referencedColumn) {
        var order = new JpaMappingAnalyzer.EntityMapping("com.acme.Order", "Order", "com.acme", List.of(),
                orderTable, null, List.of(new JpaMappingAnalyzer.ColumnMapping("id", "ID", null, "false", "false"),
                new JpaMappingAnalyzer.ColumnMapping("customer", joinColumn, referencedColumn, "false", "false")),
                List.of(), "Order.java:1", ProjectKnowledgeModel.FactStatus.OBSERVED);
        var customer = new JpaMappingAnalyzer.EntityMapping("com.acme.Customer", "Customer", "com.acme", List.of(),
                customerTable, null, List.of(new JpaMappingAnalyzer.ColumnMapping("id", "ID", null, "false", "false")),
                List.of(), "Customer.java:1", ProjectKnowledgeModel.FactStatus.OBSERVED);
        var relationship = new JpaMappingAnalyzer.RelationshipEdge("com.acme.Order", "com.acme.Customer",
                "customer", "ManyToOne", List.of(new JpaMappingAnalyzer.ColumnMapping(
                        "customer", joinColumn, referencedColumn, null, null)),
                "Order.java:8", ProjectKnowledgeModel.FactStatus.OBSERVED);
        return new JpaMappingAnalyzer.MappingReport(List.of(order, customer), List.of(relationship),
                List.of(), List.of(), 2, 100);
    }
}

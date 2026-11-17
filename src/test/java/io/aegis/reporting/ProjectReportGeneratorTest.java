package io.aegis.reporting;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectReportGeneratorTest {
    @Test
    void rendersDeterministicReportWithMetricsCyclesAndReviewStatuses() {
        var project = new ProjectKnowledgeModel(
                new ProjectKnowledgeModel.Project("demo", "Demo | Commerce", "Order platform", "commerce"),
                List.of(), List.of(new ProjectKnowledgeModel.Technology("Java", "25", "language",
                        ProjectKnowledgeModel.Layer.SOURCE)),
                List.of(
                        new ProjectKnowledgeModel.ApplicationComponent("A", "OrderService", "JAVA_CLASS", "OrderService.java:1", null),
                        new ProjectKnowledgeModel.ApplicationComponent("B", "BillingService", "JAVA_CLASS", "BillingService.java:1", null)),
                List.of(new ProjectKnowledgeModel.DataStore("db", "POSTGRESQL", "16", List.of(
                        new ProjectKnowledgeModel.DbSchema("public", List.of(
                                new ProjectKnowledgeModel.DbTable("orders", List.of(
                                        new ProjectKnowledgeModel.DbColumn("id", "BIGINT", null, null, null,
                                                false, null, "schema.sql:1"),
                                        new ProjectKnowledgeModel.DbColumn("customer_id", "BIGINT", null, null, null,
                                                false, null, "schema.sql:2")),
                                List.of(new ProjectKnowledgeModel.DbConstraint("fk_orders_customer",
                                        ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY, List.of("customer_id"),
                                        "customers", List.of("id"), "schema.sql:2")), List.of(), "schema.sql:1")))))),
                List.of(new ProjectKnowledgeModel.LogicalEntity("order", "Order", List.of(
                        new ProjectKnowledgeModel.LogicalAttribute("id", "NUMBER", true, null,
                                ProjectKnowledgeModel.FactStatus.INFERRED)), List.of(),
                        ProjectKnowledgeModel.FactStatus.INFERRED)),
                List.of(new ProjectKnowledgeModel.ApiEndpoint("GET /orders", "GET", "/orders", "listOrders",
                        "openapi.yaml", null)),
                List.of(new ProjectKnowledgeModel.Dependency("A", "B", "JAVA_INTERNAL_IMPORT", null, List.of()),
                        new ProjectKnowledgeModel.Dependency("B", "A", "JAVA_INTERNAL_IMPORT", null, List.of())),
                List.of(new ProjectKnowledgeModel.BusinessRule("rule-1", "amount | total", "Order.java:4",
                        ProjectKnowledgeModel.FactStatus.INFERRED)), null, "1.0");

        String report = new ProjectReportGenerator().generate(project);

        assertTrue(report.contains("# Aegis Modernization Report"));
        assertTrue(report.contains("Demo \\| Commerce"));
        assertTrue(report.contains("Dependency cycles"));
        assertTrue(report.contains("OrderService"));
        assertTrue(report.contains("POSTGRESQL"));
        assertTrue(report.contains("Physical data lineage"));
        assertTrue(report.contains("db.public.orders"));
        assertTrue(report.contains("db.public.customers"));
        assertTrue(report.contains("INFERRED"));
        assertTrue(report.contains("amount \\| total"));
        assertTrue(report.contains("strongly connected components"));
        assertEquals(report, new ProjectReportGenerator().generate(project));
    }

    @Test
    void rejectsNullKnowledgeModel() {
        assertThrows(NullPointerException.class, () -> new ProjectReportGenerator().generate(null));
    }
}

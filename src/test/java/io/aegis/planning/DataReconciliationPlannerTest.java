package io.aegis.planning;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataReconciliationPlannerTest {
    @Test
    void createsReadOnlyRowCountKeyRequiredColumnAndForeignKeyChecks() {
        var model = model();
        var plan = new DataReconciliationPlanner().create(model, "legacy", "target", null);

        assertEquals(2, plan.mappedTables());
        assertEquals(plan, new DataReconciliationPlanner().create(model, "legacy", "target", null));
        assertTrue(plan.checks().stream().anyMatch(c -> c.type().equals("ROW_COUNT")));
        assertTrue(plan.checks().stream().anyMatch(c -> c.type().equals("DUPLICATE_PRIMARY_KEYS")));
        assertTrue(plan.checks().stream().anyMatch(c -> c.type().equals("NULL_PRIMARY_KEYS")));
        assertTrue(plan.checks().stream().anyMatch(c -> c.type().equals("REQUIRED_COLUMN_NULLS")));
        var fk = plan.checks().stream().filter(c -> c.type().equals("ORPHAN_FOREIGN_KEYS")).findFirst().orElseThrow();
        assertTrue(fk.sourceSql().contains("\"HR\".\"ORDERS\""));
        assertTrue(fk.sourceSql().contains("\"HR\".\"CUSTOMERS\""));
        assertTrue(fk.targetSql().contains("\"public\".\"ORDERS\""));
        assertTrue(fk.targetSql().contains("\"public\".\"CUSTOMERS\""));
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.startsWith("COLUMN_TYPE_DIFFERENCE")));
        assertTrue(plan.limitations().stream().anyMatch(l -> l.contains("never connects")));
    }

    @Test
    void usesExplicitTableAndColumnMappingsForRenamedTargetObjects() {
        var mapping = new DataReconciliationPlanner.MappingDocument(List.of(
                new DataReconciliationPlanner.TableMapping("HR", "ORDERS", "public", "ORDERS_V2",
                        List.of(new DataReconciliationPlanner.ColumnMapping("ID", "ORDER_ID"),
                                new DataReconciliationPlanner.ColumnMapping("CUSTOMER_ID", "CUSTOMER_REF_ID"))),
                new DataReconciliationPlanner.TableMapping("HR", "CUSTOMERS", "public", "CUSTOMERS_V2",
                        List.of(new DataReconciliationPlanner.ColumnMapping("ID", "CUSTOMER_ID")))));
        var plan = new DataReconciliationPlanner().create(modelWithRenamedTarget(), "legacy", "target", mapping);

        var fk = plan.checks().stream().filter(c -> c.type().equals("ORPHAN_FOREIGN_KEYS")).findFirst().orElseThrow();
        assertTrue(fk.targetSql().contains("\"public\".\"ORDERS_V2\""));
        assertTrue(fk.targetSql().contains("CUSTOMER_REF_ID"));
        assertTrue(fk.targetSql().contains("\"public\".\"CUSTOMERS_V2\""));
        assertTrue(plan.diagnostics().stream().noneMatch(d -> d.startsWith("COLUMN_MAPPING_UNRESOLVED")));
    }

    @Test
    void reportsUnmappedTablesAndRequiresSupportedDialectVendors() {
        var incomplete = new DataReconciliationPlanner.MappingDocument(List.of(
                new DataReconciliationPlanner.TableMapping("HR", "MISSING", "public", "MISSING", List.of())));
        var plan = new DataReconciliationPlanner().create(model(), "legacy", "target", incomplete);
        assertEquals(0, plan.mappedTables());
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.startsWith("TABLE_MAPPING_UNRESOLVED")));
        assertThrows(IllegalArgumentException.class,
                () -> new DataReconciliationPlanner().create(modelWithUnknownVendor(), "legacy", "target", null));
    }

    @Test
    void reportsStringLengthNumericPrecisionAndScaleReductions() {
        var sourceTable = new ProjectKnowledgeModel.DbTable("PAYMENTS", List.of(
                new ProjectKnowledgeModel.DbColumn("ID", "NUMBER", null, 18, 0, false, null, "source.sql"),
                new ProjectKnowledgeModel.DbColumn("NAME", "VARCHAR2", 4000, null, null, true, null, "source.sql"),
                new ProjectKnowledgeModel.DbColumn("AMOUNT", "NUMBER", null, 18, 4, false, null, "source.sql")),
                List.of(pk("PK_PAYMENTS", "ID")), List.of(), "source.sql");
        var targetTable = new ProjectKnowledgeModel.DbTable("PAYMENTS", List.of(
                new ProjectKnowledgeModel.DbColumn("ID", "BIGINT", null, 19, 0, false, null, "target.sql"),
                new ProjectKnowledgeModel.DbColumn("NAME", "VARCHAR", 255, null, null, true, null, "target.sql"),
                new ProjectKnowledgeModel.DbColumn("AMOUNT", "NUMERIC", null, 10, 2, false, null, "target.sql")),
                List.of(pk("PK_PAYMENTS", "ID")), List.of(), "target.sql");
        var source = new ProjectKnowledgeModel.DataStore("legacy", "Oracle", "19c",
                List.of(new ProjectKnowledgeModel.DbSchema("HR", List.of(sourceTable))));
        var target = new ProjectKnowledgeModel.DataStore("target", "PostgreSQL", "16",
                List.of(new ProjectKnowledgeModel.DbSchema("public", List.of(targetTable))));
        var project = new ProjectKnowledgeModel(new ProjectKnowledgeModel.Project("capacity", "Capacity", null, null),
                List.of(), List.of(), List.of(), List.of(source, target), List.of(), List.of(), List.of(),
                List.of(), java.time.Instant.parse("2026-11-17T00:00:00Z"), "1.0");

        var plan = new DataReconciliationPlanner().create(project, "legacy", "target", null);

        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.startsWith("COLUMN_LENGTH_REDUCTION")));
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.startsWith("COLUMN_PRECISION_REDUCTION")));
        assertTrue(plan.diagnostics().stream().anyMatch(d -> d.startsWith("COLUMN_SCALE_REDUCTION")));
    }

    private static ProjectKnowledgeModel model() {
        var customerSource = table("CUSTOMERS", List.of(
                col("ID", "NUMBER", false), col("EMAIL", "VARCHAR2", true)),
                List.of(pk("PK_CUSTOMERS", "ID")));
        var orderSource = table("ORDERS", List.of(
                col("ID", "NUMBER", false), col("CUSTOMER_ID", "NUMBER", false)),
                List.of(pk("PK_ORDERS", "ID"), fk("FK_ORDERS_CUSTOMERS", "CUSTOMER_ID", "CUSTOMERS", "ID")));
        var customerTarget = table("CUSTOMERS", List.of(
                col("ID", "BIGINT", false), col("EMAIL", "VARCHAR", false)),
                List.of(pk("PK_CUSTOMERS", "ID")));
        var orderTarget = table("ORDERS", List.of(
                col("ID", "BIGINT", false), col("CUSTOMER_ID", "BIGINT", false)),
                List.of(pk("PK_ORDERS", "ID"), fk("FK_ORDERS_CUSTOMERS", "CUSTOMER_ID", "CUSTOMERS", "ID")));
        var source = new ProjectKnowledgeModel.DataStore("legacy", "Oracle", "19c", List.of(
                new ProjectKnowledgeModel.DbSchema("HR", List.of(customerSource, orderSource))));
        var target = new ProjectKnowledgeModel.DataStore("target", "PostgreSQL", "16", List.of(
                new ProjectKnowledgeModel.DbSchema("public", List.of(customerTarget, orderTarget))));
        return new ProjectKnowledgeModel(new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(), List.of(), List.of(source, target), List.of(), List.of(), List.of(),
                List.of(), Instant.parse("2026-11-17T00:00:00Z"), "1.0");
    }

    private static ProjectKnowledgeModel modelWithRenamedTarget() {
        var source = model().dataStores().getFirst();
        var customerTarget = table("CUSTOMERS_V2", List.of(
                col("CUSTOMER_ID", "BIGINT", false), col("EMAIL", "VARCHAR", false)),
                List.of(pk("PK_CUSTOMERS_V2", "CUSTOMER_ID")));
        var orderTarget = table("ORDERS_V2", List.of(
                col("ORDER_ID", "BIGINT", false), col("CUSTOMER_REF_ID", "BIGINT", false)),
                List.of(pk("PK_ORDERS_V2", "ORDER_ID"),
                        fk("FK_ORDERS_CUSTOMERS_V2", "CUSTOMER_REF_ID", "CUSTOMERS_V2", "CUSTOMER_ID")));
        var target = new ProjectKnowledgeModel.DataStore("target", "PostgreSQL", "16", List.of(
                new ProjectKnowledgeModel.DbSchema("public", List.of(customerTarget, orderTarget))));
        var base = model();
        return new ProjectKnowledgeModel(base.project(), base.artifacts(), base.technologies(), base.components(),
                List.of(source, target), base.logicalEntities(), base.apiEndpoints(), base.dependencies(),
                base.businessRules(), base.analyzedAt(), base.modelVersion());
    }

    private static ProjectKnowledgeModel modelWithUnknownVendor() {
        var base = model();
        var stores = List.of(
                new ProjectKnowledgeModel.DataStore("legacy", "H2", "2", List.of()),
                base.dataStores().get(1));
        return new ProjectKnowledgeModel(base.project(), base.artifacts(), base.technologies(), base.components(),
                stores, base.logicalEntities(), base.apiEndpoints(), base.dependencies(), base.businessRules(),
                base.analyzedAt(), base.modelVersion());
    }

    private static ProjectKnowledgeModel.DbTable table(String name, List<ProjectKnowledgeModel.DbColumn> columns,
                                                       List<ProjectKnowledgeModel.DbConstraint> constraints) {
        return new ProjectKnowledgeModel.DbTable(name, columns, constraints, List.of(), name + ".sql");
    }
    private static ProjectKnowledgeModel.DbColumn col(String name, String type, boolean nullable) {
        return new ProjectKnowledgeModel.DbColumn(name, type, 20, null, null, nullable, null, "schema.sql");
    }
    private static ProjectKnowledgeModel.DbConstraint pk(String name, String column) {
        return new ProjectKnowledgeModel.DbConstraint(name, ProjectKnowledgeModel.ConstraintType.PRIMARY_KEY,
                List.of(column), null, List.of(), "schema.sql");
    }
    private static ProjectKnowledgeModel.DbConstraint fk(String name, String source, String target, String targetColumn) {
        return new ProjectKnowledgeModel.DbConstraint(name, ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY,
                List.of(source), target, List.of(targetColumn), "schema.sql");
    }
}

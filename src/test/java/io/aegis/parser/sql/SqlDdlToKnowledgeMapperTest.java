package io.aegis.parser.sql;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlDdlToKnowledgeMapperTest {
    @Test
    void mapsPhysicalTablesColumnsConstraintsAndIndexesIntoUpkm() {
        ProjectKnowledgeModel project = ProjectKnowledgeModel.empty(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, "commerce"));
        SqlDdlParser.ParseResult parsed = new SqlDdlParser().parse("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL
                );
                CREATE INDEX idx_orders_customer ON orders(customer_id);
                """, "orders.sql", "postgresql");

        ProjectKnowledgeModel mapped = new SqlDdlToKnowledgeMapper()
                .merge(project, parsed, "orders-db", "public", "16");

        assertEquals(1, mapped.dataStores().size());
        ProjectKnowledgeModel.DataStore store = mapped.dataStores().get(0);
        assertEquals("POSTGRESQL", store.vendor());
        assertEquals("16", store.version());
        assertEquals(1, store.schemas().size());
        ProjectKnowledgeModel.DbTable table = store.schemas().get(0).tables().get(0);
        assertEquals("orders", table.name());
        assertEquals(2, table.columns().size());
        assertEquals(ProjectKnowledgeModel.ConstraintType.PRIMARY_KEY,
                table.constraints().get(0).type());
        assertEquals("idx_orders_customer", table.indexes().get(0).name());
    }

    @Test
    void refusesUnknownDialectInsteadOfInventingDatabaseVendor() {
        ProjectKnowledgeModel project = ProjectKnowledgeModel.empty(
                new ProjectKnowledgeModel.Project("demo", "Demo", null, null));
        SqlDdlParser.ParseResult parsed = new SqlDdlParser().parse(
                "CREATE TABLE sample (id BIGINT);", "sample.sql", null);

        assertThrows(IllegalArgumentException.class, () ->
                new SqlDdlToKnowledgeMapper().merge(project, parsed, "db", "public", null));
    }
}

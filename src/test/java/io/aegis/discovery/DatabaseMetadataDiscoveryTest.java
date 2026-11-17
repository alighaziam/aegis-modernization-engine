package io.aegis.discovery;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMetadataDiscoveryTest {
    @Test
    void discoversTablesColumnsPrimaryKeysForeignKeysAndUniqueIndexesInReadOnlyMode() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:aegis_metadata;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA APP");
                statement.execute("""
                        CREATE TABLE APP.CUSTOMERS (
                            ID BIGINT PRIMARY KEY,
                            EMAIL VARCHAR(255) NOT NULL UNIQUE
                        )
                        """);
                statement.execute("""
                        CREATE TABLE APP.ORDERS (
                            ID BIGINT PRIMARY KEY,
                            CUSTOMER_ID BIGINT NOT NULL,
                            CONSTRAINT FK_ORDERS_CUSTOMERS FOREIGN KEY (CUSTOMER_ID) REFERENCES APP.CUSTOMERS(ID)
                        )
                        """);
            }
            assertFalse(connection.isReadOnly());

            var report = new DatabaseMetadataDiscovery().discover(connection, "customer-db", "APP");

            assertFalse(connection.isReadOnly(), "The caller's original connection mode should be restored");
            assertEquals("customer-db", report.dataStore().id());
            assertEquals("H2", report.dataStore().vendor().toUpperCase(Locale.ROOT));
            assertEquals(2, report.tables());
            assertEquals(4, report.columns());
            assertEquals(1, report.foreignKeys());
            assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("JDBC_READ_ONLY_HINT_NOT_CONFIRMED")));
            var schema = report.dataStore().schemas().getFirst();
            assertTrue(schema.name().endsWith("APP"));
            var customers = schema.tables().stream().filter(t -> t.name().equals("CUSTOMERS")).findFirst().orElseThrow();
            assertTrue(customers.constraints().stream().anyMatch(c ->
                    c.type() == ProjectKnowledgeModel.ConstraintType.PRIMARY_KEY));
            var idColumn = customers.columns().stream().filter(c -> c.name().equals("ID")).findFirst().orElseThrow();
            var emailColumn = customers.columns().stream().filter(c -> c.name().equals("EMAIL")).findFirst().orElseThrow();
            assertNotNull(idColumn.precision());
            assertNull(idColumn.length());
            assertEquals(255, emailColumn.length());
            assertTrue(customers.indexes().stream().anyMatch(ProjectKnowledgeModel.DbIndex::unique));
            var orders = schema.tables().stream().filter(t -> t.name().equals("ORDERS")).findFirst().orElseThrow();
            var fk = orders.constraints().stream()
                    .filter(c -> c.type() == ProjectKnowledgeModel.ConstraintType.FOREIGN_KEY)
                    .findFirst().orElseThrow();
            assertEquals("CUSTOMERS", fk.referencedTable());
            assertEquals("CUSTOMER_ID", fk.columns().getFirst());
            assertEquals("ID", fk.referencedColumns().getFirst());
            assertFalse(fk.sourceLocator().contains("password"));
        }
    }

    @Test
    void returnsDiagnosticWhenNoTablesMatchSchemaFilter() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:aegis_empty;DB_CLOSE_DELAY=-1")) {
            var report = new DatabaseMetadataDiscovery().discover(connection, "empty-db", "MISSING");
            assertEquals(0, report.tables());
            assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("NO_TABLES_DISCOVERED")));
        }
    }
}

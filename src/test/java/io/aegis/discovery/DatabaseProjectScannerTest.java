package io.aegis.discovery;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseProjectScannerTest {
    @TempDir Path temp;

    @Test
    void aggregatesDdlAcrossNestedFilesAndMapsOnePhysicalDataStore() throws Exception {
        Path root = temp.resolve("database");
        Files.createDirectories(root.resolve("schema"));
        Files.createDirectories(root.resolve("target/generated"));
        Files.writeString(root.resolve("schema/01-customers.sql"), """
                CREATE TABLE customers (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(120) NOT NULL
                );
                """);
        Files.writeString(root.resolve("schema/02-orders.sql"), """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL REFERENCES customers(id)
                );
                CREATE INDEX idx_orders_customer ON orders(customer_id);
                """);
        Files.writeString(root.resolve("target/generated/ignored.sql"), "CREATE TABLE ignored (id BIGINT);");
        var base = ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null));

        var report = new DatabaseProjectScanner().scan(root, base, "postgresql", "orders-db", "public", "16");

        assertEquals(2, report.filesDiscovered());
        assertEquals(3, report.ddl().statementCount());
        assertEquals(2, report.ddl().tables().size());
        assertEquals(1, report.knowledgeModel().dataStores().size());
        assertEquals("orders-db", report.knowledgeModel().dataStores().get(0).id());
        assertTrue(report.knowledgeModel().dataStores().get(0).schemas().get(0).tables().stream()
                .anyMatch(table -> table.name().equals("orders") && table.indexes().size() == 1));
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void rejectsUnsupportedDialectAndMissingSqlFiles() throws Exception {
        Path root = temp.resolve("empty");
        Files.createDirectories(root);
        var base = ProjectKnowledgeModel.empty(new ProjectKnowledgeModel.Project("demo", "Demo", null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new DatabaseProjectScanner().scan(root, base, "postgresql", "db", "public", null));
        Files.writeString(root.resolve("schema.sql"), "CREATE TABLE sample (id BIGINT);");
        assertThrows(IllegalArgumentException.class, () ->
                new DatabaseProjectScanner().scan(root, base, "not-a-dialect", "db", "public", null));
    }
}

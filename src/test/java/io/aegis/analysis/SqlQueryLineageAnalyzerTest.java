package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlQueryLineageAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void extractsQualifiedColumnLineageFromSimpleJoinAndAliases() {
        var report = new SqlQueryLineageAnalyzer().analyzeSelect("""
                SELECT o.id AS order_id, c.name AS customer_name
                FROM sales.orders o
                JOIN sales.customers c ON o.customer_id = c.id
                WHERE o.status = 'OPEN'
                """, "queries/orders.sql", 12, model());

        assertEquals(2, report.edges().size());
        assertEquals("legacy.sales.orders", report.edges().getFirst().sourceTable());
        assertEquals("id", report.edges().getFirst().sourceColumn());
        assertEquals("order_id", report.edges().getFirst().outputColumn());
        assertEquals("c", report.edges().getLast().tableAlias());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void resolvesUnqualifiedColumnOnlyWhenExactlyOneFromTableOwnsIt() {
        var unique = new SqlQueryLineageAnalyzer().analyzeSelect(
                "SELECT name FROM orders o JOIN customers c ON o.customer_id = c.id",
                "query.sql", 1, model());
        assertEquals(1, unique.edges().size());
        assertEquals("legacy.sales.customers", unique.edges().getFirst().sourceTable());

        var ambiguous = new SqlQueryLineageAnalyzer().analyzeSelect(
                "SELECT id FROM orders o JOIN customers c ON o.customer_id = c.id",
                "query.sql", 1, model());
        assertTrue(ambiguous.edges().isEmpty());
        assertTrue(ambiguous.diagnostics().stream().anyMatch(d -> d.startsWith("AMBIGUOUS_COLUMN:")));
    }

    @Test
    void reportsUnknownAliasesColumnsAndUnsupportedExpressions() {
        var report = new SqlQueryLineageAnalyzer().analyzeSelect("""
                SELECT x.id, o.missing, COUNT(o.id) AS total
                FROM orders o
                """, "query.sql", 4, model());

        assertTrue(report.edges().isEmpty());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("UNKNOWN_TABLE_ALIAS:")));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("SOURCE_COLUMN_NOT_FOUND:")));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("UNSUPPORTED_PROJECTION:")));
    }

    @Test
    void doesNotInventLineageForSelectStarOrCteQueries() throws Exception {
        var star = new SqlQueryLineageAnalyzer().analyzeSelect(
                "SELECT o.* FROM orders o", "query.sql", 1, model());
        assertTrue(star.edges().isEmpty());
        assertTrue(star.diagnostics().stream().anyMatch(d -> d.startsWith("UNSUPPORTED_PROJECTION:")));

        Files.writeString(tempDir.resolve("queries.sql"), """
                CREATE TABLE ignored (id BIGINT);
                WITH recent AS (SELECT id FROM orders) SELECT id FROM recent;
                SELECT id FROM orders;
                """);
        var scanned = new SqlQueryLineageAnalyzer().analyzeProject(tempDir, model());
        assertEquals(1, scanned.queriesAnalyzed());
        assertTrue(scanned.diagnostics().stream().anyMatch(d -> d.startsWith("UNSUPPORTED_CTE:")));
        assertEquals(1, scanned.edges().size());
    }

    private static ProjectKnowledgeModel model() {
        var orders = new ProjectKnowledgeModel.DbTable("orders",
                List.of(column("id"), column("customer_id"), column("status")), List.of(), List.of(), "schema.sql:1");
        var customers = new ProjectKnowledgeModel.DbTable("customers",
                List.of(column("id"), column("name")), List.of(), List.of(), "schema.sql:10");
        var store = new ProjectKnowledgeModel.DataStore("legacy", "POSTGRESQL", "16",
                List.of(new ProjectKnowledgeModel.DbSchema("sales", List.of(orders, customers))));
        return new ProjectKnowledgeModel(new ProjectKnowledgeModel.Project("demo", "Demo", null, null),
                List.of(), List.of(), List.of(), List.of(store), List.of(), List.of(), List.of(), List.of(), null, "1.0");
    }

    private static ProjectKnowledgeModel.DbColumn column(String name) {
        return new ProjectKnowledgeModel.DbColumn(name, "TEXT", null, null, null, true, null, "schema.sql:1");
    }
    @Test
    void supportsQuotedIdentifiersWithoutTreatingThemAsKeywords() {
        var report = new SqlQueryLineageAnalyzer().analyzeSelect(
                "SELECT \"o\".\"id\" AS \"order_id\" FROM \"sales\".\"orders\" AS \"o\"",
                "quoted.sql", 1, model());

        assertEquals(1, report.edges().size());
        assertEquals("order_id", report.edges().getFirst().outputColumn());
        assertTrue(report.diagnostics().isEmpty());
    }

}

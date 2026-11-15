package io.aegis.parser.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlDdlParserTest {
    private final SqlDdlParser parser = new SqlDdlParser();

    @Test
    void parsesTableForeignKeyAndIndex() {
        String sql = """
                CREATE TABLE customers (
                    id BIGINT PRIMARY KEY,
                    email VARCHAR(255) NOT NULL,
                    account_id BIGINT REFERENCES accounts(id)
                );
                CREATE INDEX idx_customers_email ON customers(email);
                """;

        SqlDdlParser.ParseResult result = parser.parse(sql, "schema.sql", "postgresql");

        assertEquals("POSTGRESQL", result.vendor());
        assertEquals(1, result.tables().size());
        assertEquals("customers", result.tables().get(0).name());
        assertEquals(3, result.tables().get(0).columns().size());
        assertTrue(result.tables().get(0).constraints().stream()
                .anyMatch(c -> "FOREIGN_KEY".equals(c.type())));
        assertEquals(1, result.indexes().size());
        assertEquals(1, result.tables().get(0).indexes().size());
        assertEquals("idx_customers_email", result.tables().get(0).indexes().get(0).name());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void reportsUnsupportedStatementsInsteadOfGuessing() {
        SqlDdlParser.ParseResult result = parser.parse(
                "CREATE SEQUENCE customer_seq;", "schema.sql", "oracle");

        assertEquals(1, result.diagnostics().size());
        assertEquals("UNSUPPORTED_DDL", result.diagnostics().get(0).code());
        assertEquals("REVIEW_REQUIRED", result.diagnostics().get(0).action());
    }
}

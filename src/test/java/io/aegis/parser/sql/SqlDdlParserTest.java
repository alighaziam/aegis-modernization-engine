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
        assertEquals("BIGINT", result.tables().get(0).columns().get(2).dataType());
        assertTrue(result.tables().get(0).constraints().stream()
                .anyMatch(c -> "FOREIGN_KEY".equals(c.type())));
        assertEquals(1, result.indexes().size());
        assertEquals(1, result.tables().get(0).indexes().size());
        assertEquals("idx_customers_email", result.tables().get(0).indexes().get(0).name());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void recognizesCommonDdlAcrossRegisteredDatabaseDialects() {
        for (String dialect : new String[]{"oracle", "postgresql", "mysql", "mariadb", "sqlserver", "db2"}) {
            SqlDdlParser.ParseResult result = parser.parse(
                    "CREATE TABLE sample (id BIGINT PRIMARY KEY);", "schema.sql", dialect);
            assertNotEquals("UNKNOWN", result.vendor(), dialect);
            assertEquals(1, result.tables().size(), dialect);
        }
    }

    @Test
    void reportsUnsupportedStatementsInsteadOfGuessing() {
        SqlDdlParser.ParseResult result = parser.parse(
                "CREATE SEQUENCE customer_seq;", "schema.sql", "oracle");

        assertEquals(1, result.diagnostics().size());
        assertEquals("UNSUPPORTED_DDL", result.diagnostics().get(0).code());
        assertEquals("REVIEW_REQUIRED", result.diagnostics().get(0).action());
    }
    @Test
    void ignoresQueryAndDmlStatementsWhileParsingDdlInMixedSqlFiles() {
        String sql = """
                SELECT id FROM customers;
                INSERT INTO customers (id) VALUES (1);
                UPDATE customers SET id = 2;
                DELETE FROM customers WHERE id = 2;
                CREATE TABLE audit_log (id BIGINT PRIMARY KEY);
                """;

        SqlDdlParser.ParseResult result = parser.parse(sql, "mixed.sql", "postgresql");

        assertEquals(1, result.tables().size());
        assertEquals("audit_log", result.tables().getFirst().name());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void parsesOracleAndPostgresAlterTableAddColumnFormsWithEvidence() {
        String sql = """
                CREATE TABLE customers (id NUMBER(19) PRIMARY KEY);
                ALTER TABLE customers ADD COLUMN email VARCHAR2(255) NOT NULL;
                ALTER TABLE customers ADD (display_name VARCHAR2(80), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
                """;

        SqlDdlParser.ParseResult result = parser.parse(sql, "V2__customer_columns.sql", "oracle");

        assertEquals(4, result.tables().getFirst().columns().size());
        assertEquals("email", result.tables().getFirst().columns().get(1).name());
        assertFalse(result.tables().getFirst().columns().get(1).nullable());
        assertEquals("display_name", result.tables().getFirst().columns().get(2).name());
        assertEquals("created_at", result.tables().getFirst().columns().get(3).name());
        assertEquals("CURRENT_TIMESTAMP", result.tables().getFirst().columns().get(3).defaultExpression());
        assertTrue(result.evidence().stream().anyMatch(e -> e.subjectRef().equals("column:customers.email")));
        assertTrue(result.evidence().stream().anyMatch(e -> e.subjectRef().equals("column:customers.display_name")));
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void parsesAlterTableDropConstraintAndRecordsEvidence() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT,
                    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
                );
                ALTER TABLE orders DROP CONSTRAINT fk_orders_customer;
                """, "V3__drop_customer_fk.sql", "postgresql");

        assertTrue(result.tables().getFirst().constraints().stream()
                .noneMatch(c -> "fk_orders_customer".equalsIgnoreCase(c.name())));
        assertTrue(result.evidence().stream().anyMatch(e ->
                e.subjectRef().equals("constraint:orders.fk_orders_customer")
                        && e.extraction().equals("ALTER TABLE DROP CONSTRAINT")));
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void reportsUnknownConstraintDropsWithoutChangingTheParsedTable() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE orders (id BIGINT PRIMARY KEY);
                ALTER TABLE orders DROP CONSTRAINT fk_missing;
                """, "V3__invalid_drop.sql", "oracle");

        assertEquals(1, result.tables().getFirst().constraints().size());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> "DROP_CONSTRAINT_NOT_FOUND".equals(d.code())));
    }

    @Test
    void reportsDuplicateAlterTableColumnsInsteadOfSilentlyReplacingThem() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY, email VARCHAR(255));
                ALTER TABLE customers ADD COLUMN email VARCHAR(320);
                """, "migration.sql", "postgresql");

        assertEquals(2, result.tables().getFirst().columns().size());
        assertTrue(result.diagnostics().stream().anyMatch(d -> "DUPLICATE_COLUMN".equals(d.code())));
    }

}

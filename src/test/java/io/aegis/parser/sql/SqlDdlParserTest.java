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
    void handlesDropConstraintIfExistsWithoutDiagnosticForMissingConstraint() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE orders (id BIGINT PRIMARY KEY);
                ALTER TABLE orders DROP CONSTRAINT IF EXISTS fk_missing;
                """, "V3__idempotent_drop.sql", "postgresql");

        assertEquals(1, result.tables().getFirst().constraints().size());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        assertTrue(result.evidence().stream().noneMatch(e ->
                e.extraction().equals("ALTER TABLE DROP CONSTRAINT")));
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


    @Test
    void parsesAlterTableDropColumnAndRecordsEvidence() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY, legacy_code VARCHAR(40));
                ALTER TABLE customers DROP COLUMN legacy_code;
                """, "V4__drop_legacy_code.sql", "postgresql");

        assertEquals(1, result.tables().getFirst().columns().size());
        assertEquals("id", result.tables().getFirst().columns().getFirst().name());
        assertTrue(result.evidence().stream().anyMatch(e ->
                e.subjectRef().equals("column:customers.legacy_code")
                        && e.extraction().equals("ALTER TABLE DROP COLUMN")));
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void doesNotDropColumnsReferencedByConstraintsOrIndexes() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT,
                    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
                );
                ALTER TABLE orders DROP COLUMN customer_id;
                """, "V4__unsafe_drop.sql", "postgresql");

        assertTrue(result.tables().getFirst().columns().stream()
                .anyMatch(column -> "customer_id".equalsIgnoreCase(column.name())));
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> "DROP_COLUMN_DEPENDENCY".equals(d.code())));
    }

    @Test
    void renamesColumnAndUpdatesConstraintAndIndexReferences() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY, email VARCHAR(255) NOT NULL);
                CREATE INDEX idx_customers_email ON customers(email);
                ALTER TABLE customers RENAME COLUMN email TO email_address;
                """, "V5__rename_email.sql", "postgresql");

        assertTrue(result.tables().getFirst().columns().stream()
                .anyMatch(column -> "email_address".equalsIgnoreCase(column.name())
                        && "VARCHAR".equals(column.dataType()) && !column.nullable()));
        assertFalse(result.tables().getFirst().columns().stream()
                .anyMatch(column -> "email".equalsIgnoreCase(column.name())));
        assertTrue(result.indexes().getFirst().columns().contains("email_address"));
        assertTrue(result.tables().getFirst().indexes().getFirst().columns().contains("email_address"));
        assertTrue(result.evidence().stream().anyMatch(e ->
                e.subjectRef().equals("column:customers.email_address")
                        && e.extraction().equals("ALTER TABLE RENAME COLUMN")));
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void rejectsRenameToExistingColumnWithoutMutatingTable() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY, email VARCHAR(255), name VARCHAR(80));
                ALTER TABLE customers RENAME COLUMN email TO name;
                """, "V5__invalid_rename.sql", "postgresql");

        assertTrue(result.tables().getFirst().columns().stream()
                .anyMatch(column -> "email".equalsIgnoreCase(column.name())));
        assertTrue(result.diagnostics().stream().anyMatch(d -> "DUPLICATE_COLUMN".equals(d.code())));
        assertTrue(result.evidence().stream().noneMatch(e ->
                e.extraction().equals("ALTER TABLE RENAME COLUMN")));
    }

    @Test
    void reportsMissingRenameSourceWithoutInventingColumn() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY);
                ALTER TABLE customers RENAME COLUMN missing TO replacement;
                """, "V5__missing_rename.sql", "oracle");

        assertEquals(1, result.tables().getFirst().columns().size());
        assertTrue(result.diagnostics().stream().anyMatch(d -> "RENAME_COLUMN_NOT_FOUND".equals(d.code())));
        assertTrue(result.evidence().stream().noneMatch(e ->
                e.extraction().equals("ALTER TABLE RENAME COLUMN")));
    }

    @Test
    void handlesDropColumnIfExistsWithoutDiagnosticForMissingColumn() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY);
                ALTER TABLE customers DROP COLUMN IF EXISTS retired_code;
                """, "V4__idempotent_drop.sql", "postgresql");

        assertEquals(1, result.tables().getFirst().columns().size());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }


    @Test
    void doesNotDropColumnsReferencedByIndexesDeclaredEarlier() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY, email VARCHAR(255));
                CREATE INDEX idx_customers_email ON customers(email);
                ALTER TABLE customers DROP COLUMN email;
                """, "V4__indexed_drop.sql", "postgresql");

        assertTrue(result.tables().getFirst().columns().stream()
                .anyMatch(column -> "email".equalsIgnoreCase(column.name())));
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> "DROP_COLUMN_DEPENDENCY".equals(d.code())));
    }

    @Test
    void renamesTableAndUpdatesForeignKeyAndIndexReferences() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY);
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT,
                    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
                );
                CREATE INDEX idx_customers_id ON customers(id);
                ALTER TABLE customers RENAME TO clients;
                """, "V6__rename_customers.sql", "postgresql");

        assertTrue(result.tables().stream().anyMatch(table -> "clients".equalsIgnoreCase(table.name())));
        assertFalse(result.tables().stream().anyMatch(table -> "customers".equalsIgnoreCase(table.name())));
        var foreignKey = result.tables().stream().filter(table -> "orders".equalsIgnoreCase(table.name()))
                .findFirst().orElseThrow().constraints().stream()
                .filter(constraint -> "fk_orders_customer".equalsIgnoreCase(constraint.name()))
                .findFirst().orElseThrow();
        assertEquals("clients", foreignKey.referencedTable());
        assertEquals("clients", result.indexes().getFirst().tableName());
        assertTrue(result.tables().stream().filter(table -> "clients".equalsIgnoreCase(table.name()))
                .findFirst().orElseThrow().indexes().stream()
                .anyMatch(index -> "clients".equalsIgnoreCase(index.tableName())));
        assertTrue(result.evidence().stream().anyMatch(e ->
                e.subjectRef().equals("table:clients") && e.extraction().equals("ALTER TABLE RENAME TO")));
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void rejectsRenameToExistingTableWithoutMutatingTheModel() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT PRIMARY KEY);
                CREATE TABLE clients (id BIGINT PRIMARY KEY);
                ALTER TABLE customers RENAME TO clients;
                """, "V6__duplicate_rename.sql", "postgresql");

        assertTrue(result.tables().stream().anyMatch(table -> "customers".equalsIgnoreCase(table.name())));
        assertTrue(result.tables().stream().anyMatch(table -> "clients".equalsIgnoreCase(table.name())));
        assertTrue(result.diagnostics().stream().anyMatch(d -> "DUPLICATE_TABLE".equals(d.code())));
        assertTrue(result.evidence().stream().noneMatch(e -> e.extraction().equals("ALTER TABLE RENAME TO")));
    }

    @Test
    void reportsMissingTableRenameWithoutInventingTable() {
        SqlDdlParser.ParseResult result = parser.parse(
                "ALTER TABLE missing RENAME TO replacement;", "V6__missing_rename.sql", "oracle");

        assertTrue(result.tables().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> "RENAME_TABLE_NOT_FOUND".equals(d.code())));
        assertTrue(result.evidence().isEmpty());
    }



    @Test
    void appliesPostgresAlterColumnTypeNullabilityAndDefaultChangesInOrder() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (
                    id BIGINT NOT NULL,
                    email VARCHAR(80),
                    status VARCHAR(10) DEFAULT 'new'
                );
                ALTER TABLE customers ALTER COLUMN email TYPE VARCHAR(255);
                ALTER TABLE customers ALTER COLUMN email SET NOT NULL;
                ALTER TABLE customers ALTER COLUMN email DROP NOT NULL;
                ALTER TABLE customers ALTER COLUMN status SET DEFAULT 'active';
                ALTER TABLE customers ALTER COLUMN status DROP DEFAULT;
                """, "V7__alter_customer_columns.sql", "postgresql");

        SqlDdlParser.Table customers = result.tables().getFirst();
        SqlDdlParser.Column email = customers.columns().stream()
                .filter(column -> "email".equalsIgnoreCase(column.name())).findFirst().orElseThrow();
        SqlDdlParser.Column status = customers.columns().stream()
                .filter(column -> "status".equalsIgnoreCase(column.name())).findFirst().orElseThrow();

        assertEquals("VARCHAR", email.dataType());
        assertEquals(255, email.length());
        assertTrue(email.nullable());
        assertNull(status.defaultExpression());
        assertEquals(5, result.evidence().stream()
                .filter(e -> e.extraction().startsWith("ALTER TABLE ALTER COLUMN")).count());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void appliesOracleModifyColumnFormsAndPreservesUnspecifiedNullabilityAndDefaults() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (
                    id NUMBER(9) NOT NULL,
                    name VARCHAR2(40) DEFAULT 'guest'
                );
                ALTER TABLE customers MODIFY (id NUMBER(12), name VARCHAR2(100) NOT NULL);
                """, "V7__oracle_modify_customer.sql", "oracle");

        SqlDdlParser.Table customers = result.tables().getFirst();
        SqlDdlParser.Column id = customers.columns().stream()
                .filter(column -> "id".equalsIgnoreCase(column.name())).findFirst().orElseThrow();
        SqlDdlParser.Column name = customers.columns().stream()
                .filter(column -> "name".equalsIgnoreCase(column.name())).findFirst().orElseThrow();

        assertEquals("NUMBER", id.dataType());
        assertEquals(12, id.length());
        assertFalse(id.nullable());
        assertEquals(100, name.length());
        assertFalse(name.nullable());
        assertEquals("'guest'", name.defaultExpression());
        assertEquals(2, result.evidence().stream()
                .filter(e -> e.extraction().equals("ALTER TABLE MODIFY COLUMN")).count());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void reportsUnknownAlteredColumnsAndRequiresReviewForUsingExpressions() {
        SqlDdlParser.ParseResult result = parser.parse("""
                CREATE TABLE customers (id BIGINT, amount NUMBER(8,2));
                ALTER TABLE customers ALTER COLUMN missing SET NOT NULL;
                ALTER TABLE customers ALTER COLUMN amount TYPE NUMERIC(12,2) USING amount::numeric;
                """, "V7__review_column_changes.sql", "postgresql");

        SqlDdlParser.Column amount = result.tables().getFirst().columns().stream()
                .filter(column -> "amount".equalsIgnoreCase(column.name())).findFirst().orElseThrow();
        assertEquals("NUMERIC", amount.dataType());
        assertEquals(12, amount.length());
        assertEquals(2, amount.scale());
        assertTrue(result.diagnostics().stream().anyMatch(d -> "ALTER_COLUMN_NOT_FOUND".equals(d.code())));
        assertTrue(result.diagnostics().stream().anyMatch(d -> "ALTER_COLUMN_USING_REVIEW".equals(d.code())));
    }

    @Test
    void reportsMissingTableForAlterColumnAndModifyStatements() {
        SqlDdlParser.ParseResult result = parser.parse("""
                ALTER TABLE missing ALTER COLUMN id SET NOT NULL;
                ALTER TABLE another MODIFY (name VARCHAR2(50));
                """, "V7__missing_tables.sql", "oracle");

        assertTrue(result.tables().isEmpty());
        assertEquals(2, result.diagnostics().stream()
                .filter(d -> "ALTER_TABLE_BEFORE_CREATE".equals(d.code())).count());
    }

}

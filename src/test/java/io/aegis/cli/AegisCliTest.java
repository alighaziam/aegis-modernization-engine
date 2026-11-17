package io.aegis.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AegisCliTest {
    @TempDir
    Path tempDir;

    @Test
    void helpReturnsSuccess() {
        assertEquals(0, AegisCli.run(new String[]{"--help"}));
    }

    @Test
    void dialectListingCommandReturnsSuccess() {
        assertEquals(0, AegisCli.run(new String[]{"list-db-dialects"}));
    }

    @Test
    void parseDdlCommandCompletesSuccessfully() throws Exception {
        Path sql = tempDir.resolve("schema.sql");
        Files.writeString(sql, "CREATE TABLE sample (id BIGINT PRIMARY KEY);");

        assertEquals(0, AegisCli.run(new String[]{"parse-ddl", sql.toString(), "postgresql"}));
    }


    @Test
    void oracleJournalCommandsValidateTheirArgumentsWithoutOpeningDatabase() {
        assertEquals(2, AegisCli.run(new String[]{"store-evidence"}));
        assertEquals(2, AegisCli.run(new String[]{"list-evidence"}));
        assertEquals(2, AegisCli.run(new String[]{"store-conflict-event"}));
        assertEquals(2, AegisCli.run(new String[]{"list-conflicts"}));
    }

    @Test
    void rejectsUnknownCommand() {
        assertEquals(2, AegisCli.run(new String[]{"unknown"}));
    }
    @Test
    void mapDdlCommandBuildsProjectKnowledgeModel() throws Exception {
        Path intake = tempDir.resolve("project.json");
        Files.writeString(intake, """
                {
                  "schemaVersion": "1.0",
                  "project": {"id": "demo", "name": "Demo"},
                  "intake": {
                    "batchId": "batch-1",
                    "mode": "single",
                    "artifacts": [{
                      "artifactId": "ddl",
                      "category": "database_metadata",
                      "format": "sql",
                      "origin": "manual"
                    }]
                  }
                }
                """);
        Path sql = tempDir.resolve("schema.sql");
        Files.writeString(sql, "CREATE TABLE sample (id BIGINT PRIMARY KEY);");

        assertEquals(0, AegisCli.run(new String[]{
                "map-ddl", intake.toString(), sql.toString(), "postgresql", "main-db", "public", "16"}));
    }

    @Test
    void analyzeJavaCommandAddsTypesToProjectKnowledgeModel() throws Exception {
        Path intake = tempDir.resolve("java-project.json");
        Files.writeString(intake, """
                {
                  "schemaVersion": "1.0",
                  "project": {"id": "demo", "name": "Demo"},
                  "intake": {
                    "batchId": "java-batch",
                    "mode": "single",
                    "artifacts": [{
                      "artifactId": "source",
                      "category": "source_code",
                      "format": "java",
                      "origin": "manual"
                    }]
                  }
                }
                """);
        Path java = tempDir.resolve("CustomerService.java");
        Files.writeString(java, """
                package com.acme;
                import java.util.List;
                public class CustomerService {
                    public List<String> find() { return List.of(); }
                }
                """);

        assertEquals(0, AegisCli.run(new String[]{
                "analyze-java", java.toString(), intake.toString()}));
    }

    @Test
    void ddlMigrationCommandReturnsReviewExitCode() throws Exception {
        Path ddl = tempDir.resolve("oracle.sql");
        Files.writeString(ddl, "CREATE TABLE CUSTOMER (ID NUMBER(9) NOT NULL);");
        java.io.PrintStream original = System.out;
        try {
            System.setOut(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
            assertEquals(3, AegisCli.run(new String[]{
                    "plan-ddl-migration", ddl.toString(), "oracle", "public"}));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void analyzeProjectCommandScansNestedJavaSources() throws Exception {
        Path root = tempDir.resolve("project-root");
        Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Files.writeString(root.resolve("src/main/java/com/acme/Customer.java"), """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity @Table(name = "CUSTOMERS")
                public class Customer {}
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/CustomerController.java"), """
                package com.acme;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                @RestController
                @RequestMapping("/api/customers")
                public class CustomerController {
                    @GetMapping("/{id}")
                    public Customer find() { return null; }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/Order.java"), """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;
                @Entity public class Order {
                    @ManyToOne @JoinColumn(name = "CUSTOMER_ID")
                    Customer customer;
                }
                """);
        Path intake = tempDir.resolve("project-scan.json");
        Files.writeString(intake, """
                {
                  "schemaVersion": "1.0",
                  "project": {"id": "scan-demo", "name": "Scan Demo"},
                  "intake": {
                    "batchId": "scan-batch",
                    "mode": "single",
                    "artifacts": [{
                      "artifactId": "source",
                      "category": "source_code",
                      "format": "java",
                      "origin": "manual"
                    }]
                  }
                }
                """);

        assertEquals(0, AegisCli.run(new String[]{
                "analyze-project", root.toString(), intake.toString()}));
    }

    @Test
    void analyzeDatabaseCommandScansNestedDdlFiles() throws Exception {
        Path root = tempDir.resolve("database-root");
        Files.createDirectories(root.resolve("db"));
        Files.writeString(root.resolve("db/schema.sql"), "CREATE TABLE sample (id BIGINT PRIMARY KEY);");
        Path intake = tempDir.resolve("database-scan.json");
        Files.writeString(intake, """
                {
                  "schemaVersion": "1.0",
                  "project": {"id": "db-demo", "name": "Database Demo"},
                  "intake": {
                    "batchId": "db-batch",
                    "mode": "single",
                    "artifacts": [{
                      "artifactId": "ddl",
                      "category": "database_metadata",
                      "format": "sql",
                      "origin": "manual"
                    }]
                  }
                }
                """);
        assertEquals(0, AegisCli.run(new String[]{
                "analyze-database", root.toString(), intake.toString(),
                "postgresql", "main-db", "public", "16"}));
    }

    @Test
    void renderReportCommandWritesMarkdownWithoutOverwritingInput() throws Exception {
        Path input = tempDir.resolve("upkm.json");
        Files.writeString(input, """
                {
                  "project": {"id": "demo", "name": "Demo", "description": null, "businessDomain": null},
                  "artifacts": [], "technologies": [], "components": [], "dataStores": [],
                  "logicalEntities": [], "apiEndpoints": [], "dependencies": [], "businessRules": [],
                  "analyzedAt": "2026-11-17T00:00:00Z", "modelVersion": "1.0"
                }
                """);
        Path output = tempDir.resolve("reports/architecture.md");

        assertEquals(0, AegisCli.run(new String[]{"render-report", input.toString(), output.toString()}));
        assertTrue(Files.readString(output).contains("# Aegis Modernization Report"));
        assertEquals(2, AegisCli.run(new String[]{"render-report", input.toString(), input.toString()}));
    }

    @Test
    void analyzeLineageCommandReadsUpkmAndReportsDeclaredForeignKeys() throws Exception {
        Path input = tempDir.resolve("lineage-upkm.json");
        Files.writeString(input, """
                {
                  "project": {"id": "demo", "name": "Demo", "description": null, "businessDomain": null},
                  "artifacts": [], "technologies": [], "components": [],
                  "dataStores": [{
                    "id": "legacy", "vendor": "ORACLE", "version": "19c",
                    "schemas": [{
                      "name": "SALES",
                      "tables": [{
                        "name": "ORDERS", "columns": [],
                        "constraints": [{
                          "name": "FK_ORDERS_CUSTOMER", "type": "FOREIGN_KEY",
                          "columns": ["CUSTOMER_ID"], "referencedTable": "CUSTOMER",
                          "referencedColumns": ["ID"], "sourceLocator": "schema.sql:1"
                        }],
                        "indexes": [], "sourceLocator": "schema.sql:1"
                      }]
                    }]
                  }],
                  "logicalEntities": [], "apiEndpoints": [], "dependencies": [], "businessRules": [],
                  "analyzedAt": "2026-11-17T00:00:00Z", "modelVersion": "1.0"
                }
                """);

        assertEquals(0, AegisCli.run(new String[]{"analyze-lineage", input.toString()}));
    }

    @Test
    void analyzeWorkspaceCommandProducesUpkmSummaryAndMarkdownReport() throws Exception {
        Path root = tempDir.resolve("workspace-project");
        Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Files.createDirectories(root.resolve("db"));
        Files.writeString(root.resolve("src/main/java/com/acme/Customer.java"), """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity @Table(name = "CUSTOMERS")
                public class Customer {}
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/Order.java"), """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;
                @Entity @Table(name = "ORDERS")
                public class Order {
                    @ManyToOne @JoinColumn(name = "CUSTOMER_ID", referencedColumnName = "ID")
                    Customer customer;
                }
                """);
        Files.writeString(root.resolve("db/schema.sql"), """
                CREATE TABLE customers (id BIGINT PRIMARY KEY);
                CREATE TABLE orders (
                    customer_id BIGINT,
                    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
                );
                """);
        Files.writeString(root.resolve("openapi.yaml"), """
                openapi: 3.0.3
                info:
                  title: Customer API
                  version: 1.0.0
                paths:
                  /customers:
                    get:
                      operationId: listCustomers
                """);
        Path intake = tempDir.resolve("workspace-intake.json");
        Files.writeString(intake, """
                {
                  "schemaVersion": "1.0",
                  "project": {"id": "workspace-demo", "name": "Workspace Demo"},
                  "intake": {
                    "batchId": "workspace-batch",
                    "mode": "single",
                    "artifacts": [{
                      "artifactId": "source",
                      "category": "source_code",
                      "format": "java",
                      "origin": "manual"
                    }]
                  }
                }
                """);
        Path output = tempDir.resolve("workspace-output");

        assertEquals(0, AegisCli.run(new String[]{
                "analyze-workspace", root.toString(), intake.toString(), output.toString(),
                "postgresql", "main-db", "public", "16"}));
        assertTrue(Files.readString(output.resolve("upkm.json")).contains("Customer"));
        assertTrue(Files.readString(output.resolve("upkm.json")).contains("listCustomers"));
        assertTrue(Files.readString(output.resolve("architecture.md")).contains("# Aegis Modernization Report"));
        assertTrue(Files.readString(output.resolve("analysis-summary.json")).contains("workspace-demo"));
        assertTrue(Files.readString(output.resolve("analysis-summary.json")).contains("\"openApiSpecifications\""));
        assertTrue(Files.readString(output.resolve("analysis-summary.json")).contains("\"jpaEntities\""));
        assertTrue(Files.readString(output.resolve("analysis-summary.json")).contains("\"jpaRelationships\""));
        assertTrue(Files.readString(output.resolve("architecture.md")).contains("ORM / JPA mappings"));
        assertTrue(Files.readString(output.resolve("architecture.md")).contains("com.acme.Order"));
        assertTrue(Files.readString(output.resolve("openapi-inventory.json")).contains("Customer"));
        assertTrue(Files.readString(output.resolve("orm-mappings.json")).contains("com.acme.Order"));
        assertTrue(Files.readString(output.resolve("orm-mappings.json")).contains("CUSTOMER_ID"));
        assertTrue(Files.readString(output.resolve("jpa-schema-reconciliation.json")).contains("JPA_ENTITY_TABLE_MATCHED"));
        assertTrue(Files.readString(output.resolve("query-lineage.json")).contains("\"queriesAnalyzed\""));
        assertTrue(Files.readString(output.resolve("architecture.md")).contains("SQL query column lineage"));
        assertTrue(Files.readString(output.resolve("analysis-summary.json")).contains("\"jpaSchemaMatches\""));
        assertTrue(Files.readString(output.resolve("analysis-summary.json")).contains("\"springEndpointsDiscovered\""));
        assertTrue(Files.readString(output.resolve("upkm.json")).contains("/api/customers/{id}"));
        assertTrue(Files.readString(output.resolve("spring-api-inventory.json")).contains("/api/customers/{id}"));
        assertTrue(Files.readString(output.resolve("architecture.md")).contains("Spring controller routes"));
    }

    @Test
    void analyzeJpaCommandExtractsEntityAndRelationshipMappings() throws Exception {
        Path root = tempDir.resolve("jpa-project");
        Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Files.writeString(root.resolve("src/main/java/com/acme/Customer.java"), """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity @Table(name = "CUSTOMERS")
                class Customer {}
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/Order.java"), """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;
                @Entity class Order {
                    @ManyToOne @JoinColumn(name = "CUSTOMER_ID")
                    Customer customer;
                }
                """);

        assertEquals(0, AegisCli.run(new String[]{"analyze-jpa", root.toString()}));
    }

    @Test
    void analyzeJpaCommandValidatesArguments() {
        assertEquals(2, AegisCli.run(new String[]{"analyze-jpa"}));
    }

    @Test
    void reconcileJpaCommandComparesSerializedUpkmAndOrmMappings() throws Exception {
        Path model = tempDir.resolve("reconcile-upkm.json");
        Files.writeString(model, """
                {
                  "project": {"id": "demo", "name": "Demo", "description": null, "businessDomain": null},
                  "artifacts": [], "technologies": [], "components": [], "dataStores": [],
                  "logicalEntities": [], "apiEndpoints": [], "dependencies": [], "businessRules": [],
                  "analyzedAt": "2026-11-17T00:00:00Z", "modelVersion": "1.0"
                }
                """);
        Path mappings = tempDir.resolve("orm-mappings.json");
        Files.writeString(mappings, """
                {
                  "entities": [], "relationships": [], "diagnostics": [], "limitations": [],
                  "filesScanned": 0, "bytesRead": 0
                }
                """);

        assertEquals(0, AegisCli.run(new String[]{"reconcile-jpa", model.toString(), mappings.toString()}));
    }

    @Test
    void reconcileJpaCommandValidatesArguments() {
        assertEquals(2, AegisCli.run(new String[]{"reconcile-jpa"}));
    }

    @Test
    void planWorkspaceCommandBuildsMigrationPlanFromAnalyzedUpkm() throws Exception {
        Path input = tempDir.resolve("planned-upkm.json");
        Files.writeString(input, """
                {
                  "project": {"id": "plan-demo", "name": "Plan Demo", "description": null, "businessDomain": null},
                  "artifacts": [], "technologies": [], "components": [],
                  "dataStores": [{"id": "legacy-db", "vendor": "ORACLE", "version": "19c", "schemas": []}],
                  "logicalEntities": [], "apiEndpoints": [], "dependencies": [], "businessRules": [],
                  "analyzedAt": "2026-11-17T00:00:00Z", "modelVersion": "1.0"
                }
                """);
        Path output = tempDir.resolve("plan-output");

        assertEquals(0, AegisCli.run(new String[]{
                "plan-workspace", input.toString(), "25", "Spring Boot", "4.0.0",
                "PostgreSQL", "React", "Kubernetes", output.toString()}));
        assertTrue(Files.readString(output.resolve("migration-plan.json")).contains("PostgreSQL"));
        assertTrue(Files.readString(output.resolve("migration-plan.md")).contains("Oracle-to-PostgreSQL"));
    }

    @Test
    void analyzeQueryLineageCommandExtractsColumnMappingsFromProjectSql() throws Exception {
        Path root = tempDir.resolve("query-project");
        Files.createDirectories(root.resolve("sql"));
        Files.writeString(root.resolve("sql/orders-query.sql"), """
                SELECT o.id AS order_id, c.name AS customer_name
                FROM sales.orders o
                JOIN sales.customers c ON o.customer_id = c.id;
                """);
        Path model = tempDir.resolve("query-upkm.json");
        Files.writeString(model, """
                {
                  "project": {"id": "demo", "name": "Demo", "description": null, "businessDomain": null},
                  "artifacts": [], "technologies": [], "components": [],
                  "dataStores": [{
                    "id": "legacy", "vendor": "POSTGRESQL", "version": "16",
                    "schemas": [{
                      "name": "sales",
                      "tables": [
                        {"name": "orders", "columns": [
                          {"name": "id", "dataType": "BIGINT", "length": null, "scale": null, "precision": null, "nullable": false, "defaultExpression": null, "sourceLocator": "schema.sql:1"},
                          {"name": "customer_id", "dataType": "BIGINT", "length": null, "scale": null, "precision": null, "nullable": false, "defaultExpression": null, "sourceLocator": "schema.sql:1"}
                        ], "constraints": [], "indexes": [], "sourceLocator": "schema.sql:1"},
                        {"name": "customers", "columns": [
                          {"name": "id", "dataType": "BIGINT", "length": null, "scale": null, "precision": null, "nullable": false, "defaultExpression": null, "sourceLocator": "schema.sql:2"},
                          {"name": "name", "dataType": "TEXT", "length": null, "scale": null, "precision": null, "nullable": true, "defaultExpression": null, "sourceLocator": "schema.sql:2"}
                        ], "constraints": [], "indexes": [], "sourceLocator": "schema.sql:2"}
                      ]
                    }]
                  }],
                  "logicalEntities": [], "apiEndpoints": [], "dependencies": [], "businessRules": [],
                  "analyzedAt": "2026-11-17T00:00:00Z", "modelVersion": "1.0"
                }
                """);
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(output));
            assertEquals(0, AegisCli.run(new String[]{"analyze-query-lineage", root.toString(), model.toString()}));
        } finally {
            System.setOut(original);
        }
        assertTrue(output.toString().contains("order_id"));
        assertTrue(output.toString().contains("customer_name"));
    }

    @Test
    void analyzeQueryLineageCommandValidatesArguments() {
        assertEquals(2, AegisCli.run(new String[]{"analyze-query-lineage"}));
    }

    @Test
    void analyzeSpringApiCommandDiscoversControllerRoutes() throws Exception {
        Path root = tempDir.resolve("spring-api-project");
        Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Files.writeString(root.resolve("src/main/java/com/acme/StatusController.java"), """
                package com.acme;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                class StatusController {
                    @GetMapping("/status")
                    String status() { return "ok"; }
                }
                """);
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(output));
            assertEquals(0, AegisCli.run(new String[]{"analyze-spring-api", root.toString()}));
        } finally {
            System.setOut(original);
        }
        assertTrue(output.toString().contains("/api/status"));
        assertTrue(output.toString().contains("GET"));
    }

    @Test
    void analyzeSpringApiCommandValidatesArguments() {
        assertEquals(2, AegisCli.run(new String[]{"analyze-spring-api"}));
    }

}

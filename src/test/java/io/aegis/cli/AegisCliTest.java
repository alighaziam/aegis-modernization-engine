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
        Files.writeString(root.resolve("src/main/java/com/acme/Customer.java"),
                "package com.acme; public class Customer {}");
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

}

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
    void parseDdlCommandCompletesSuccessfully() throws Exception {
        Path sql = tempDir.resolve("schema.sql");
        Files.writeString(sql, "CREATE TABLE sample (id BIGINT PRIMARY KEY);");
        assertEquals(0, AegisCli.run(new String[]{"parse-ddl", sql.toString(), "postgresql"}));
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
    void migrateJavaImportsWritesNewFileAndPreservesOriginal() throws Exception {
        Path input = tempDir.resolve("LegacyEntity.java");
        Path output = tempDir.resolve("LegacyEntityJakarta.java");
        String original = "import javax.persistence.Entity; class LegacyEntity {}";
        Files.writeString(input, original);

        assertEquals(0, AegisCli.run(new String[]{
                "migrate-java-imports", input.toString(), output.toString()}));
        assertEquals(original, Files.readString(input));
        assertTrue(Files.readString(output).contains("import jakarta.persistence.Entity;"));
    }

    @Test
    void migrateJavaImportsDoesNotOverwriteExistingOutput() throws Exception {
        Path input = tempDir.resolve("LegacyEntity.java");
        Path output = tempDir.resolve("LegacyEntityJakarta.java");
        Files.writeString(input, "import javax.persistence.Entity; class LegacyEntity {}");
        Files.writeString(output, "keep-me");

        assertEquals(1, AegisCli.run(new String[]{
                "migrate-java-imports", input.toString(), output.toString()}));
        assertEquals("keep-me", Files.readString(output));
    }
}

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

}

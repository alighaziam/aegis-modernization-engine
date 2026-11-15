package io.aegis.intake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntakeServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsMissingModeWithDomainValidationError() throws Exception {
        Path request = tempDir.resolve("intake.json");
        Files.writeString(request, """
                {
                  "schemaVersion": "1.0",
                  "project": {"id": "demo", "name": "Demo"},
                  "intake": {
                    "batchId": "batch-1",
                    "artifacts": [{
                      "artifactId": "a1",
                      "category": "source_code",
                      "format": "text",
                      "origin": "manual"
                    }]
                  }
                }
                """);

        IntakeService.IntakeException error = assertThrows(
                IntakeService.IntakeException.class,
                () -> new IntakeService(tempDir.resolve("store")).ingest(request));

        assertTrue(error.getMessage().contains("Unsupported intake mode"));
    }

    @Test
    void registersArtifactByContentHash() throws Exception {
        Path artifact = tempDir.resolve("schema.sql");
        Files.writeString(artifact, "CREATE TABLE sample (id BIGINT PRIMARY KEY);");
        Path request = tempDir.resolve("intake.json");
        Files.writeString(request, """
                {
                  "schemaVersion": "1.0",
                  "project": {"id": "demo", "name": "Demo"},
                  "intake": {
                    "batchId": "batch-1",
                    "mode": "single",
                    "artifacts": [{
                      "artifactId": "schema",
                      "category": "database_metadata",
                      "format": "sql",
                      "origin": "user",
                      "location": "schema.sql"
                    }]
                  }
                }
                """);

        IntakeService.IntakeReport report =
                new IntakeService(tempDir.resolve("store")).ingest(request);

        assertEquals(1, report.registeredCount());
        assertEquals("REGISTERED", report.artifacts().get(0).status());
        assertTrue(Files.exists(tempDir.resolve("store/objects")
                .resolve(report.artifacts().get(0).sha256())));
        assertTrue(Files.exists(tempDir.resolve("store/metadata/demo-batch-1.json")));
    }
}

package io.aegis.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsAndReadsEvidenceWithInstantTimestamp() throws Exception {
        EvidenceStore store = new EvidenceStore(tempDir);
        Instant createdAt = Instant.parse("2026-11-15T10:00:00Z");
        EvidenceRecord record = new EvidenceRecord(
                "ev-1", "table:customers", EvidenceRecord.Kind.DIRECT,
                new EvidenceRecord.Source("sql", "schema-1", "schema.sql",
                        new EvidenceRecord.Locator("schema.sql", 1, 4, null, null, null, "customers", null, null)),
                new EvidenceRecord.Method("sql-ddl-parser", "1.0", "CREATE_TABLE", "1.0", "DDL table declaration"),
                new EvidenceRecord.Confidence(EvidenceRecord.ConfidenceLevel.HIGH, "Direct CREATE TABLE statement"),
                EvidenceRecord.ReviewStatus.NOT_REQUIRED, "run-1", createdAt, List.of(), null);

        store.append(record);

        assertEquals(record, store.find("ev-1"));
        assertEquals(createdAt, store.readAll().get(0).createdAt());
        assertThrows(IllegalArgumentException.class, () -> store.append(record));
    }
}

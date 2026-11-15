package io.aegis.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConflictRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsOpenAndResolvedEventsWithInstantTimestamps() throws Exception {
        ConflictRegistry registry = new ConflictRegistry(tempDir);
        ConflictRegistry.ConflictEvent opened = registry.open(
                "table:customers", List.of("evidence-a", "evidence-b"),
                "Conflicting customer identifiers", "HIGH");

        assertNotNull(opened.occurredAt());
        assertNotNull(registry.findOpen(opened.eventId()));

        ConflictRegistry.ConflictEvent resolved = registry.resolve(
                opened.eventId(), "Use source-of-record mapping", "reviewer",
                "evidence-confirmation");

        assertNotNull(resolved.occurredAt());
        assertNull(registry.findOpen(opened.eventId()));
        assertEquals(2, registry.history().size());
    }

    @Test
    void rejectsConflictWithFewerThanTwoEvidenceReferences() throws Exception {
        ConflictRegistry registry = new ConflictRegistry(tempDir);
        assertThrows(IllegalArgumentException.class,
                () -> registry.open("table:customers", List.of("only-one"), "conflict", "LOW"));
    }
}

package io.aegis.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Conflict resolutions are new events; competing claims and history remain intact. */
public final class ConflictRegistry {
    private final Path journal;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public ConflictRegistry(Path directory) throws IOException {
        Files.createDirectories(directory);
        journal = directory.resolve("conflicts.jsonl");
        if (!Files.exists(journal)) Files.createFile(journal);
    }

    public synchronized ConflictEvent open(String subjectRef, List\u003cString\u003e evidenceIds,
                                           String description, String impact) throws IOException {
        if (evidenceIds == null || evidenceIds.size() \u003c 2) throw new IllegalArgumentException("A conflict requires at least two competing evidence references");
        ConflictEvent event = new ConflictEvent(UUID.randomUUID().toString(), subjectRef, List.copyOf(evidenceIds),
                description, impact, EventType.OPENED, null, null, Instant.now());
        append(event);
        return event;
    }

    public synchronized ConflictEvent resolve(String conflictId, String resolution, String resolvedBy,
                                              String confirmationEvidenceId) throws IOException {
        if (findOpen(conflictId) == null) throw new IllegalArgumentException("No open conflict found: " + conflictId);
        ConflictEvent event = new ConflictEvent(UUID.randomUUID().toString(), conflictId, List.of(), resolution,
                null, EventType.RESOLVED, resolvedBy, confirmationEvidenceId, Instant.now());
        append(event);
        return event;
    }

    public synchronized List\u003cConflictEvent\u003e history() throws IOException {
        List\u003cConflictEvent\u003e events = new ArrayList\u003c\u003e();
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) if (!line.isBlank())
            events.add(mapper.readValue(line, ConflictEvent.class));
        return List.copyOf(events);
    }

    public synchronized ConflictEvent findOpen(String conflictId) throws IOException {
        ConflictEvent opened = null;
        for (ConflictEvent event : history()) {
            if (event.eventId().equals(conflictId) && event.type() == EventType.OPENED) opened = event;
            if (event.subjectRef().equals(conflictId) && event.type() == EventType.RESOLVED) opened = null;
        }
        return opened;
    }

    private void append(ConflictEvent event) throws IOException {
        Files.writeString(journal, mapper.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public enum EventType { OPENED, RESOLVED }
    public record ConflictEvent(String eventId, String subjectRef, List\u003cString\u003e competingEvidenceIds,
                                String description, String impact, EventType type, String resolvedBy,
                                String confirmationEvidenceId, Instant occurredAt) {}
}

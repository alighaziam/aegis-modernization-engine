package io.aegis.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Append-only JSONL store. Existing evidence is never edited or silently replaced. */
public final class EvidenceStore {
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public EvidenceStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.file = directory.resolve("evidence.jsonl");
        if (!Files.exists(file)) Files.createFile(file);
    }

    public synchronized void append(EvidenceRecord record) throws IOException {
        if (find(record.evidenceId()) != null) throw new IllegalArgumentException("Duplicate evidenceId: " + record.evidenceId());
        byte[] json = (mapper.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            channel.write(java.nio.ByteBuffer.wrap(json));
            channel.force(true);
        }
    }

    public synchronized EvidenceRecord find(String evidenceId) throws IOException {
        for (EvidenceRecord record : readAll()) if (record.evidenceId().equals(evidenceId)) return record;
        return null;
    }

    public synchronized List\u003cEvidenceRecord\u003e readAll() throws IOException {
        List\u003cEvidenceRecord\u003e records = new ArrayList\u003e();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) records.add(mapper.readValue(line, EvidenceRecord.class));
        }
        return List.copyOf(records);
    }
}

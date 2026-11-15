package io.aegis.persistence;

import io.aegis.evidence.ConflictRegistry;
import java.util.List;

/** Append-only conflict event persistence boundary. */
public interface ConflictEventRepository {
    void append(String projectId, ConflictRegistry.ConflictEvent event) throws PersistenceException;
    List<ConflictRegistry.ConflictEvent> history(String projectId) throws PersistenceException;
}

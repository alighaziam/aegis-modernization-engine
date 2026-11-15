package io.aegis.persistence;

import io.aegis.evidence.EvidenceRecord;
import java.util.List;

/** Append-only evidence persistence boundary. */
public interface EvidenceRepository {
    void append(String projectId, EvidenceRecord evidence) throws PersistenceException;
    List<EvidenceRecord> findByAnalysisRun(String projectId, String analysisRunId) throws PersistenceException;
}

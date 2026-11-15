package io.aegis.persistence;

import io.aegis.domain.ProjectKnowledgeModel;
import java.util.Optional;

/** Database-vendor-neutral persistence boundary for Aegis project snapshots. */
public interface ProjectRepository extends AutoCloseable {
    void saveSnapshot(ProjectKnowledgeModel model) throws PersistenceException;
    Optional<ProjectKnowledgeModel> findLatestSnapshot(String projectId) throws PersistenceException;
    void verifyConnection() throws PersistenceException;
    @Override default void close() {}
}

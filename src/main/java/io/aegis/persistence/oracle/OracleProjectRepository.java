package io.aegis.persistence.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.persistence.PersistenceException;
import io.aegis.persistence.ProjectRepository;

import javax.sql.DataSource;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/** Oracle-backed, append-only project analysis snapshot repository. */
public final class OracleProjectRepository implements ProjectRepository {
    private static final String UPSERT_PROJECT = """
            MERGE INTO AEGIS_PROJECTS target
            USING (SELECT ? AS PROJECT_ID FROM DUAL) source
               ON (target.PROJECT_ID = source.PROJECT_ID)
            WHEN MATCHED THEN UPDATE SET
               target.PROJECT_NAME = ?, target.DESCRIPTION = ?, target.BUSINESS_DOMAIN = ?,
               target.UPDATED_AT = SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT
               (PROJECT_ID, PROJECT_NAME, DESCRIPTION, BUSINESS_DOMAIN, CREATED_AT, UPDATED_AT)
               VALUES (?, ?, ?, ?, SYSTIMESTAMP, SYSTIMESTAMP)
            """;
    private static final String INSERT_RUN = """
            INSERT INTO AEGIS_ANALYSIS_RUNS
              (RUN_ID, PROJECT_ID, MODEL_VERSION, ANALYZED_AT, MODEL_JSON)
            VALUES (?, ?, ?, ?, ?)
            """;
    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public OracleProjectRepository(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
    }

    @Override
    public void saveSnapshot(ProjectKnowledgeModel model) {
        java.util.Objects.requireNonNull(model, "model");
        String payload;
        try { payload = mapper.writeValueAsString(model); }
        catch (Exception e) { throw new PersistenceException("Could not serialize project knowledge model", e); }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement project = connection.prepareStatement(UPSERT_PROJECT)) {
                    String id = model.project().id();
                    project.setString(1, id);
                    project.setString(2, model.project().name());
                    project.setString(3, model.project().description());
                    project.setString(4, model.project().businessDomain());
                    project.setString(5, id);
                    project.setString(6, model.project().name());
                    project.setString(7, model.project().description());
                    project.setString(8, model.project().businessDomain());
                    project.executeUpdate();
                }
                try (PreparedStatement run = connection.prepareStatement(INSERT_RUN)) {
                    run.setString(1, java.util.UUID.randomUUID().toString());
                    run.setString(2, model.project().id());
                    run.setString(3, model.modelVersion());
                    run.setTimestamp(4, Timestamp.from(model.analyzedAt()));
                    run.setCharacterStream(5, new StringReader(payload), payload.length());
                    run.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (Exception e) {
            throw new PersistenceException("Oracle snapshot transaction failed", e);
        }
    }

    @Override
    public Optional<ProjectKnowledgeModel> findLatestSnapshot(String projectId) {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
        String sql = """
                SELECT MODEL_JSON
                  FROM AEGIS_ANALYSIS_RUNS
                 WHERE PROJECT_ID = ?
                 ORDER BY ANALYZED_AT DESC, RUN_ID DESC
                 FETCH FIRST 1 ROWS ONLY
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                try (Reader reader = result.getCharacterStream(1)) {
                    return Optional.of(mapper.readValue(reader, ProjectKnowledgeModel.class));
                }
            }
        } catch (Exception e) {
            throw new PersistenceException("Could not load the latest Oracle project snapshot", e);
        }
    }

    @Override
    public void verifyConnection() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM DUAL");
             ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1)
                throw new PersistenceException("Oracle health check returned an unexpected result");
        } catch (SQLException e) {
            throw new PersistenceException("Oracle database connection check failed", e);
        }
    }
}

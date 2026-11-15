package io.aegis.persistence.oracle;

import io.aegis.persistence.ArtifactRepository;
import io.aegis.persistence.PersistenceException;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Oracle BLOB adapter for immutable source artifacts. */
public final class OracleArtifactRepository implements ArtifactRepository {
    private static final String INSERT = """
            INSERT INTO AEGIS_ARTIFACTS
              (PROJECT_ID, ARTIFACT_ID, CATEGORY, FORMAT, ORIGIN, SHA256, SIZE_BYTES, CONTENT_BLOB, CREATED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT = """
            SELECT CATEGORY, FORMAT, ORIGIN, SHA256, CONTENT_BLOB, CREATED_AT
              FROM AEGIS_ARTIFACTS
             WHERE PROJECT_ID = ? AND ARTIFACT_ID = ?
            """;
    private final DataSource dataSource;

    public OracleArtifactRepository(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
    }

    @Override
    public void save(ArtifactContent artifact) {
        java.util.Objects.requireNonNull(artifact, "artifact");
        byte[] bytes = artifact.content();
        if (bytes.length > 100L * 1024L * 1024L)
            throw new IllegalArgumentException("Artifact exceeds the 100 MiB persistence limit");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, artifact.projectId());
            statement.setString(2, artifact.artifactId());
            statement.setString(3, artifact.category());
            statement.setString(4, artifact.format());
            statement.setString(5, artifact.origin());
            statement.setString(6, artifact.sha256());
            statement.setLong(7, bytes.length);
            statement.setBinaryStream(8, new ByteArrayInputStream(bytes), bytes.length);
            statement.setTimestamp(9, Timestamp.from(artifact.createdAt()));
            statement.executeUpdate();
        } catch (Exception e) {
            throw new PersistenceException("Could not persist immutable artifact in Oracle", e);
        }
    }

    @Override
    public Optional<ArtifactContent> find(String projectId, String artifactId) {
        if (projectId == null || projectId.isBlank() || artifactId == null || artifactId.isBlank())
            throw new IllegalArgumentException("projectId and artifactId are required");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setString(1, projectId);
            statement.setString(2, artifactId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                byte[] bytes = result.getBytes("CONTENT_BLOB");
                Instant createdAt = result.getTimestamp("CREATED_AT").toInstant();
                return Optional.of(new ArtifactContent(projectId, artifactId,
                        result.getString("CATEGORY"), result.getString("FORMAT"),
                        result.getString("ORIGIN"), result.getString("SHA256"), bytes, createdAt));
            }
        } catch (Exception e) {
            throw new PersistenceException("Could not load immutable artifact from Oracle", e);
        }
    }
}

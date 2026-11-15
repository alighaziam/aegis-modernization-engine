package io.aegis.persistence.oracle;

import io.aegis.persistence.ArtifactRepository;
import io.aegis.persistence.PersistenceException;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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


    /** Streams a file directly to Oracle without loading up to 100 MiB into heap memory. */
    public StoredArtifact saveFile(String projectId, String artifactId, String category,
                                   String format, String origin, Path file) {
        if (projectId == null || projectId.isBlank() || artifactId == null || artifactId.isBlank()
                || category == null || category.isBlank() || format == null || format.isBlank()
                || origin == null || origin.isBlank())
            throw new IllegalArgumentException("Artifact identity and classification fields are required");
        java.util.Objects.requireNonNull(file, "file");
        try {
            Path source = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Artifact file is not a regular file: " + source);
            long size = Files.size(source);
            if (size > 100L * 1024L * 1024L) throw new IllegalArgumentException("Artifact exceeds the 100 MiB persistence limit");
            String digest = sha256(source);
            try (Connection connection = dataSource.getConnection();
                 InputStream input = Files.newInputStream(source);
                 PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, projectId);
                statement.setString(2, artifactId);
                statement.setString(3, category);
                statement.setString(4, format);
                statement.setString(5, origin);
                statement.setString(6, digest);
                statement.setLong(7, size);
                statement.setBinaryStream(8, input, size);
                statement.setTimestamp(9, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            return new StoredArtifact(projectId, artifactId, digest, size);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegal) throw illegal;
            throw new PersistenceException("Could not stream immutable artifact into Oracle", e);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record StoredArtifact(String projectId, String artifactId, String sha256, long sizeBytes) {}

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

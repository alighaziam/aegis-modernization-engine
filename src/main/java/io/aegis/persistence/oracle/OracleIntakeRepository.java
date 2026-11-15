package io.aegis.persistence.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.domain.ProjectKnowledgeModel;
import io.aegis.intake.IntakeService;
import io.aegis.persistence.PersistenceException;
import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

/** Atomically persists an intake snapshot and all currently supplied artifacts in Oracle. */
public final class OracleIntakeRepository {
    private static final long MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L;
    private static final String UPSERT_PROJECT = """
            MERGE INTO AEGIS_PROJECTS target
            USING (SELECT ? AS PROJECT_ID FROM DUAL) source
               ON (target.PROJECT_ID = source.PROJECT_ID)
            WHEN MATCHED THEN UPDATE SET target.PROJECT_NAME = ?, target.DESCRIPTION = ?,
               target.BUSINESS_DOMAIN = ?, target.UPDATED_AT = SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT
               (PROJECT_ID, PROJECT_NAME, DESCRIPTION, BUSINESS_DOMAIN, CREATED_AT, UPDATED_AT)
               VALUES (?, ?, ?, ?, SYSTIMESTAMP, SYSTIMESTAMP)
            """;
    private static final String INSERT_RUN = """
            INSERT INTO AEGIS_ANALYSIS_RUNS (RUN_ID, PROJECT_ID, MODEL_VERSION, ANALYZED_AT, MODEL_JSON)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ARTIFACT = """
            INSERT INTO AEGIS_ARTIFACTS
              (PROJECT_ID, ARTIFACT_ID, CATEGORY, FORMAT, ORIGIN, SHA256, SIZE_BYTES, CONTENT_BLOB, CREATED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public OracleIntakeRepository(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
    }

    public IntakeSaveResult save(IntakeService.IntakeRequest request, ProjectKnowledgeModel model, Path intakeFile) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(model, "model");
        try {
            Path source = intakeFile.toAbsolutePath().normalize();
            Path base = source.getParent().toRealPath();
            int persisted = 0;
            int pending = 0;
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    upsertProject(connection, model);
                    insertSnapshot(connection, model);
                    for (IntakeService.ArtifactDescriptor artifact : request.intake().artifacts()) {
                        Path file = resolveSuppliedArtifact(artifact, base);
                        if (file == null) { pending++; continue; }
                        insertArtifact(connection, request.project().id(), artifact, file);
                        persisted++;
                    }
                    connection.commit();
                } catch (Exception failure) {
                    try { connection.rollback(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            }
            return new IntakeSaveResult(request.project().id(), persisted, pending, Instant.now());
        } catch (Exception e) {
            if (e instanceof PersistenceException pe) throw pe;
            throw new PersistenceException("Oracle intake transaction failed; no partial intake was committed", e);
        }
    }

    private void upsertProject(Connection c, ProjectKnowledgeModel model) throws Exception {
        try (PreparedStatement s = c.prepareStatement(UPSERT_PROJECT)) {
            String id = model.project().id();
            s.setString(1, id); s.setString(2, model.project().name());
            s.setString(3, model.project().description()); s.setString(4, model.project().businessDomain());
            s.setString(5, id); s.setString(6, model.project().name());
            s.setString(7, model.project().description()); s.setString(8, model.project().businessDomain());
            s.executeUpdate();
        }
    }

    private void insertSnapshot(Connection c, ProjectKnowledgeModel model) throws Exception {
        String payload = mapper.writeValueAsString(model);
        try (PreparedStatement s = c.prepareStatement(INSERT_RUN)) {
            s.setString(1, java.util.UUID.randomUUID().toString());
            s.setString(2, model.project().id()); s.setString(3, model.modelVersion());
            s.setTimestamp(4, Timestamp.from(model.analyzedAt()));
            s.setCharacterStream(5, new java.io.StringReader(payload), payload.length());
            s.executeUpdate();
        }
    }

    private Path resolveSuppliedArtifact(IntakeService.ArtifactDescriptor a, Path base) throws Exception {
        if (a.location() == null || a.location().isBlank()) return null;
        Path declared = Path.of(a.location());
        Path candidate = (declared.isAbsolute() ? declared : base.resolve(declared)).toAbsolutePath().normalize();
        if (!candidate.startsWith(base)) throw new IllegalArgumentException("Artifact path escapes intake directory: " + a.artifactId());
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return null;
        Path real = candidate.toRealPath();
        if (!real.startsWith(base)) throw new IllegalArgumentException("Artifact symlink escapes intake directory: " + a.artifactId());
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Artifact is not a regular file: " + a.artifactId());
        return real;
    }

    private void insertArtifact(Connection c, String projectId, IntakeService.ArtifactDescriptor a, Path file) throws Exception {
        long size = Files.size(file);
        if (size > MAX_ARTIFACT_BYTES) throw new IllegalArgumentException("Artifact exceeds 100 MiB limit: " + a.artifactId());
        String digest = sha256(file);
        if (a.sizeBytes() != null && a.sizeBytes() != size) throw new IllegalArgumentException("Artifact size mismatch: " + a.artifactId());
        if (a.sha256() != null && !a.sha256().equalsIgnoreCase(digest))
            throw new IllegalArgumentException("Artifact SHA-256 mismatch: " + a.artifactId());
        try (InputStream in = Files.newInputStream(file); PreparedStatement s = c.prepareStatement(INSERT_ARTIFACT)) {
            s.setString(1, projectId); s.setString(2, a.artifactId()); s.setString(3, a.category());
            s.setString(4, a.format()); s.setString(5, a.origin()); s.setString(6, digest);
            s.setLong(7, size); s.setBinaryStream(8, in, size); s.setTimestamp(9, Timestamp.from(Instant.now()));
            s.executeUpdate();
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) d.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }

    public record IntakeSaveResult(String projectId, int persistedArtifacts, int pendingArtifacts, Instant committedAt) {}
}

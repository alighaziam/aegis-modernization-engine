package io.aegis.persistence;

import java.time.Instant;
import java.util.Optional;

/** Vendor-neutral, immutable artifact content persistence boundary. */
public interface ArtifactRepository {
    void save(ArtifactContent artifact) throws PersistenceException;
    Optional<ArtifactContent> find(String projectId, String artifactId) throws PersistenceException;

    record ArtifactContent(String projectId, String artifactId, String category, String format,
                           String origin, String sha256, byte[] content, Instant createdAt) {
        public ArtifactContent {
            if (blank(projectId) || blank(artifactId) || blank(category) || blank(format) || blank(origin))
                throw new IllegalArgumentException("Artifact identity and classification fields are required");
            if (content == null) throw new IllegalArgumentException("Artifact content is required");
            content = content.clone();
            String calculated = OracleArtifactDigest.sha256(content);
            if (sha256 == null || !sha256.equalsIgnoreCase(calculated))
                throw new IllegalArgumentException("Artifact SHA-256 does not match content");
            sha256 = calculated;
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }
        @Override public byte[] content() { return content.clone(); }
        public long sizeBytes() { return content.length; }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
}

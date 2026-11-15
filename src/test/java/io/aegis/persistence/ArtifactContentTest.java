package io.aegis.persistence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactContentTest {
    @Test
    void verifiesContentHashAndDefensivelyCopiesBytes() {
        byte[] bytes = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = OracleArtifactDigest.sha256(bytes);
        ArtifactRepository.ArtifactContent artifact = new ArtifactRepository.ArtifactContent(
                "project", "artifact", "source_code", "java", "upload", digest, bytes, null);
        bytes[0] = 'X';
        assertArrayEquals("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), artifact.content());
        assertEquals(5, artifact.sizeBytes());
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRepository.ArtifactContent(
                "project", "artifact-2", "source_code", "java", "upload", "0".repeat(64), new byte[]{1}, null));
    }

    @Test
    void requiresArtifactIdentityAndContent() {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactRepository.ArtifactContent(
                "", "id", "source", "java", "upload", OracleArtifactDigest.sha256(new byte[0]), new byte[0], null));
    }
}

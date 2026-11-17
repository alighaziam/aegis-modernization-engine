package io.aegis.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ValidationEngineTest {
    @TempDir Path temp;
    @Test void validatesGeneratedArtifactHash() throws Exception {
        Path file=temp.resolve("out.txt");
        byte[] bytes="generated".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file,bytes);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var report=new ValidationEngine().validate(temp,List.of(new ValidationEngine.ExpectedArtifact("out.txt",hash)));
        assertTrue(report.valid());
    }
    @Test
    void reportsTraversalMalformedHashesAndDuplicateManifestEntries() throws Exception {
        Path file = temp.resolve("out.txt");
        byte[] bytes = "generated".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        var report = new ValidationEngine().validate(temp, List.of(
                new ValidationEngine.ExpectedArtifact("../outside.txt", hash),
                new ValidationEngine.ExpectedArtifact("out.txt", "not-a-sha256"),
                new ValidationEngine.ExpectedArtifact("out.txt", hash)));

        assertFalse(report.valid());
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("PATH_TRAVERSAL")));
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("INVALID_EXPECTED_HASH")));
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("DUPLICATE_MANIFEST_PATH")));
    }

    @Test
    void rejectsSymlinkThatResolvesOutsideProjectRoot() throws Exception {
        Path outside = temp.resolveSibling("outside-artifact.txt");
        Files.writeString(outside, "outside");
        Path link = temp.resolve("outside-link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }
        byte[] bytes = Files.readAllBytes(outside);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var report = new ValidationEngine().validate(temp,
                List.of(new ValidationEngine.ExpectedArtifact("outside-link.txt", hash)));
        assertFalse(report.valid());
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("SYMLINK_ESCAPE")));
    }

}

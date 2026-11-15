package io.aegis.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Validates generated artifacts against their recorded SHA-256 values before integration testing. */
public final class ValidationEngine {
    public ValidationReport validate(Path root, List<ExpectedArtifact> expected) throws IOException {
        Path realRoot = root.toRealPath();
        List<ValidationFinding> findings = new ArrayList<>();
        for (ExpectedArtifact artifact : expected) {
            Path path = realRoot.resolve(artifact.relativePath()).normalize();
            if (!path.startsWith(realRoot)) {
                findings.add(new ValidationFinding("PATH_TRAVERSAL", artifact.relativePath(), "Artifact path escapes project root.", "ERROR"));
                continue;
            }
            if (!Files.isRegularFile(path)) {
                findings.add(new ValidationFinding("MISSING_ARTIFACT", artifact.relativePath(), "Expected output file is missing.", "ERROR"));
                continue;
            }
            Path actualPath = path.toRealPath();
            if (!actualPath.startsWith(realRoot)) {
                findings.add(new ValidationFinding("SYMLINK_ESCAPE", artifact.relativePath(), "Artifact resolves outside project root.", "ERROR"));
                continue;
            }
            String actual = sha256(Files.readAllBytes(actualPath));
            if (!actual.equalsIgnoreCase(artifact.sha256()))
                findings.add(new ValidationFinding("HASH_MISMATCH", artifact.relativePath(), "Generated artifact differs from its manifest.", "ERROR"));
            else findings.add(new ValidationFinding("ARTIFACT_VALID", artifact.relativePath(), "SHA-256 matches manifest.", "INFO"));
        }
        long errors = findings.stream().filter(f -> f.severity().equals("ERROR")).count();
        return new ValidationReport(errors == 0, findings, List.of(
                "This stage validates artifact integrity only.",
                "Run project compilation, unit, integration, security and performance tests before production cutover."));
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public record ExpectedArtifact(String relativePath, String sha256) {}
    public record ValidationFinding(String code, String path, String message, String severity) {}
    public record ValidationReport(boolean valid, List<ValidationFinding> findings, List<String> nextChecks) {
        public ValidationReport { findings=List.copyOf(findings); nextChecks=List.copyOf(nextChecks); }
    }
}

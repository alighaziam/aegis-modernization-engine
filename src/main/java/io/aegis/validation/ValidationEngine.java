package io.aegis.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates generated artifacts against their recorded SHA-256 values before integration testing. */
public final class ValidationEngine {
    public ValidationReport validate(Path root, List<ExpectedArtifact> expected) throws IOException {
        if (root == null) throw new IllegalArgumentException("Project root is required");
        if (expected == null) throw new IllegalArgumentException("Expected artifact manifest is required");
        Path realRoot = root.toRealPath();
        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Project root must be a directory");

        List<ValidationFinding> findings = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (ExpectedArtifact artifact : expected) {
            if (artifact == null || artifact.relativePath() == null || artifact.relativePath().isBlank()) {
                findings.add(error("INVALID_MANIFEST", "<unknown>", "Artifact path is required."));
                continue;
            }
            String suppliedPath = artifact.relativePath();
            Path relative;
            try {
                if (suppliedPath.startsWith("/") || suppliedPath.matches("^[A-Za-z]:[\\\\/].*"))
                    throw new IllegalArgumentException("Absolute paths are not allowed");
                relative = Path.of(suppliedPath.replace('\\', '/')).normalize();
            } catch (RuntimeException invalidPath) {
                findings.add(error("INVALID_MANIFEST_PATH", suppliedPath, "Artifact path is invalid."));
                continue;
            }
            if (relative.isAbsolute() || relative.toString().isBlank() || relative.startsWith("..")) {
                findings.add(error("PATH_TRAVERSAL", suppliedPath, "Artifact path escapes project root."));
                continue;
            }
            String normalizedKey = relative.toString().replace('\\', '/');
            if (!seenPaths.add(normalizedKey)) {
                findings.add(error("DUPLICATE_MANIFEST_PATH", suppliedPath, "Artifact appears more than once in the manifest."));
                continue;
            }
            if (artifact.sha256() == null || !artifact.sha256().matches("(?i)[0-9a-f]{64}")) {
                findings.add(error("INVALID_EXPECTED_HASH", suppliedPath, "Expected SHA-256 must contain exactly 64 hexadecimal characters."));
                continue;
            }

            Path path = realRoot.resolve(relative).normalize();
            if (!path.startsWith(realRoot)) {
                findings.add(error("PATH_TRAVERSAL", suppliedPath, "Artifact path escapes project root."));
                continue;
            }
            if (!Files.isRegularFile(path)) {
                findings.add(error("MISSING_ARTIFACT", suppliedPath, "Expected output file is missing or is not a regular file."));
                continue;
            }
            Path actualPath = path.toRealPath();
            if (!actualPath.startsWith(realRoot)) {
                findings.add(error("SYMLINK_ESCAPE", suppliedPath, "Artifact resolves outside project root."));
                continue;
            }
            try {
                String actual = sha256(actualPath);
                if (!actual.equalsIgnoreCase(artifact.sha256()))
                    findings.add(error("HASH_MISMATCH", suppliedPath, "Generated artifact differs from its manifest."));
                else findings.add(new ValidationFinding("ARTIFACT_VALID", suppliedPath,
                        "SHA-256 matches manifest.", "INFO"));
            } catch (IOException unreadable) {
                findings.add(error("ARTIFACT_READ_ERROR", suppliedPath,
                        "Could not read expected artifact: " + unreadable.getMessage()));
            }
        }
        long errors = findings.stream().filter(f -> f.severity().equals("ERROR")).count();
        return new ValidationReport(errors == 0, findings, List.of(
                "This stage validates artifact integrity only.",
                "Run project compilation, unit, integration, security and performance tests before production cutover."));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ValidationFinding error(String code, String path, String message) {
        return new ValidationFinding(code, path, message, "ERROR");
    }

    public record ExpectedArtifact(String relativePath, String sha256) {}
    public record ValidationFinding(String code, String path, String message, String severity) {}
    public record ValidationReport(boolean valid, List<ValidationFinding> findings, List<String> nextChecks) {
        public ValidationReport {
            findings = List.copyOf(findings);
            nextChecks = List.copyOf(nextChecks);
        }
    }
}

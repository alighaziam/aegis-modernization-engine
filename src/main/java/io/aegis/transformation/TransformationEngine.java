package io.aegis.transformation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Applies explicit, preconditioned text transformations and creates a local rollback journal. */
public final class TransformationEngine {
    public TransformationResult apply(Path projectRoot, Path rollbackRoot, List<TextReplacement> changes) throws IOException {
        Path root = projectRoot.toRealPath();
        Files.createDirectories(rollbackRoot);
        Path backups = rollbackRoot.toAbsolutePath().normalize();
        List<AppliedChange> applied = new ArrayList<>();
        try {
        for (TextReplacement change : changes) {
            Path target = root.resolve(change.relativePath()).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target))
                throw new IOException("Unsafe or missing transformation target: " + change.relativePath());
            Path real = target.toRealPath();
            if (!real.startsWith(root)) throw new IOException("Symbolic-link escape is not allowed: " + change.relativePath());
            byte[] original = Files.readAllBytes(real);
            String beforeHash = sha256(original);
            if (change.expectedSha256() != null && !change.expectedSha256().equalsIgnoreCase(beforeHash))
                throw new IOException("Precondition hash mismatch: " + change.relativePath());
            String text = new String(original, StandardCharsets.UTF_8);
            int occurrences = count(text, change.expectedText());
            if (occurrences != change.expectedOccurrences())
                throw new IOException("Expected " + change.expectedOccurrences() + " occurrences but found " + occurrences + " in " + change.relativePath());
            String transformed = text.replace(change.expectedText(), change.replacementText());
            Path backup = backups.resolve(change.relativePath()).normalize();
            if (!backup.startsWith(backups)) throw new IOException("Unsafe rollback path");
            Files.createDirectories(backup.getParent());
            Files.copy(real, backup, StandardCopyOption.REPLACE_EXISTING);
            Path temp = Files.createTempFile(real.getParent(), ".aegis-", ".tmp");
            try {
                Files.writeString(temp, transformed, StandardCharsets.UTF_8);
                Files.move(temp, real, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
            applied.add(new AppliedChange(change.relativePath(), beforeHash, sha256(transformed.getBytes(StandardCharsets.UTF_8)),
                    backup.toString(), occurrences));
        }
        } catch (IOException | RuntimeException failure) {
            for (int i = applied.size() - 1; i >= 0; i--) {
                AppliedChange change = applied.get(i);
                try {
                    Path target = root.resolve(change.relativePath()).normalize();
                    Path backup = Path.of(change.backupPath());
                    if (target.startsWith(root) && Files.isRegularFile(backup))
                        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            }
            throw failure;
        }
        return new TransformationResult(applied, true);
    }

    public void rollback(TransformationResult result, Path projectRoot) throws IOException {
        Path root = projectRoot.toRealPath();
        List<AppliedChange> reversed = new ArrayList<>(result.applied());
        java.util.Collections.reverse(reversed);
        for (AppliedChange change : reversed) {
            Path target = root.resolve(change.relativePath()).normalize();
            Path backup = Path.of(change.backupPath()).toRealPath();
            if (!target.startsWith(root)) throw new IOException("Unsafe rollback target");
            if (!sha256(Files.readAllBytes(target)).equals(change.afterSha256()))
                throw new IOException("Rollback refused because file changed after transformation: " + change.relativePath());
            if (!sha256(Files.readAllBytes(backup)).equals(change.beforeSha256()))
                throw new IOException("Rollback backup integrity check failed: " + change.relativePath());
            Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int count(String text, String token) {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("expectedText cannot be empty");
        int count=0, from=0, index;
        while ((index=text.indexOf(token, from)) >= 0) { count++; from=index+token.length(); }
        return count;
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record TextReplacement(String relativePath, String expectedText, String replacementText,
                                  int expectedOccurrences, String expectedSha256) {}
    public record AppliedChange(String relativePath, String beforeSha256, String afterSha256,
                                String backupPath, int occurrences) {}
    public record TransformationResult(List<AppliedChange> applied, boolean rollbackAvailable) {
        public TransformationResult { applied=List.copyOf(applied); }
    }
}

package io.aegis.transformation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Applies explicit, preconditioned text transformations and creates a local rollback journal. */
public final class TransformationEngine {
    public TransformationResult apply(Path projectRoot, Path rollbackRoot, List<TextReplacement> changes) throws IOException {
        if (projectRoot == null || rollbackRoot == null || changes == null)
            throw new IllegalArgumentException("Project root, rollback root and changes are required");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Project root must be a directory");
        Files.createDirectories(rollbackRoot);
        Path backups = rollbackRoot.toRealPath();
        if (backups.startsWith(root) || root.startsWith(backups))
            throw new IOException("Rollback storage must be separate from the project tree");

        List<AppliedChange> applied = new ArrayList<>();
        Set<String> changedPaths = new HashSet<>();
        try {
            for (TextReplacement change : changes) {
                validateChange(change);
                Path relative = safeRelative(change.relativePath());
                String key = relative.toString().replace('\\', '/');
                if (!changedPaths.add(key)) throw new IOException("Duplicate transformation target: " + change.relativePath());

                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(target))
                    throw new IOException("Unsafe or missing transformation target: " + change.relativePath());
                rejectSymlinkParents(root, relative);
                Path real = target.toRealPath();
                if (!real.startsWith(root)) throw new IOException("Symbolic-link escape is not allowed: " + change.relativePath());

                byte[] original = Files.readAllBytes(real);
                String beforeHash = sha256(original);
                if (change.expectedSha256() != null && !change.expectedSha256().equalsIgnoreCase(beforeHash))
                    throw new IOException("Precondition hash mismatch: " + change.relativePath());
                String text = new String(original, StandardCharsets.UTF_8);
                int occurrences = count(text, change.expectedText());
                if (occurrences != change.expectedOccurrences())
                    throw new IOException("Expected " + change.expectedOccurrences() + " occurrences but found "
                            + occurrences + " in " + change.relativePath());
                byte[] transformed = text.replace(change.expectedText(), change.replacementText())
                        .getBytes(StandardCharsets.UTF_8);

                Path backup = backups.resolve(relative).normalize();
                if (!backup.startsWith(backups)) throw new IOException("Unsafe rollback path");
                Files.createDirectories(backup.getParent());
                Path backupParent = backup.getParent().toRealPath();
                if (!backupParent.startsWith(backups))
                    throw new IOException("Rollback path resolves outside rollback storage");
                if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Rollback backup already exists: " + backup);
                Files.copy(real, backup);

                AppliedChange journalEntry = new AppliedChange(change.relativePath(), beforeHash,
                        sha256(transformed), backup.toString(), occurrences);
                applied.add(journalEntry);
                atomicWrite(real, transformed);
            }
        } catch (IOException | RuntimeException failure) {
            for (int i = applied.size() - 1; i >= 0; i--) {
                AppliedChange change = applied.get(i);
                try {
                    Path relative = safeRelative(change.relativePath());
                    Path target = root.resolve(relative).normalize();
                    Path backup = Path.of(change.backupPath()).toRealPath();
                    if (!target.startsWith(root) || !backup.startsWith(backups)
                            || Files.isSymbolicLink(target))
                        throw new IOException("Rollback journal path validation failed");
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        return new TransformationResult(applied, true);
    }

    public void rollback(TransformationResult result, Path projectRoot) throws IOException {
        if (result == null || !result.rollbackAvailable())
            throw new IllegalArgumentException("A rollback-capable transformation result is required");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Project root must be a directory");

        List<RestoreOperation> operations = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (AppliedChange change : result.applied()) {
            Path relative = safeRelative(change.relativePath());
            String key = relative.toString().replace('\\', '/');
            if (!targets.add(key)) throw new IOException("Duplicate rollback target: " + change.relativePath());
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target))
                throw new IOException("Unsafe rollback target: " + change.relativePath());
            rejectSymlinkParents(root, relative);
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(root))
                throw new IOException("Rollback target resolves outside project root: " + change.relativePath());
            if (!sha256(Files.readAllBytes(realTarget)).equals(change.afterSha256()))
                throw new IOException("Rollback refused because file changed after transformation: " + change.relativePath());

            Path backupPath = Path.of(change.backupPath());
            if (Files.isSymbolicLink(backupPath) || !Files.isRegularFile(backupPath, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Rollback backup is missing or unsafe: " + change.relativePath());
            Path backup = backupPath.toRealPath();
            if (backup.startsWith(root))
                throw new IOException("Rollback backup must be outside the project tree");
            if (!sha256(Files.readAllBytes(backup)).equals(change.beforeSha256()))
                throw new IOException("Rollback backup integrity check failed: " + change.relativePath());
            operations.add(new RestoreOperation(realTarget, backup));
        }

        // Preflight every target and backup before modifying any project file.
        for (RestoreOperation operation : operations) {
            byte[] original = Files.readAllBytes(operation.backup());
            atomicWrite(operation.target(), original);
        }
    }

    private static Path safeRelative(String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("Transformation path is required");
        try {
            if (value.startsWith("/") || value.matches("^[A-Za-z]:[\\\\/].*"))
                throw new IOException("Absolute transformation paths are not allowed");
            Path relative = Path.of(value.replace('\\', '/')).normalize();
            if (relative.isAbsolute() || relative.toString().isBlank() || relative.startsWith(".."))
                throw new IOException("Unsafe relative path: " + value);
            return relative;
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IOException("Invalid transformation path: " + value, invalid);
        }
    }

    private static void rejectSymlinkParents(Path root, Path relative) throws IOException {
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current))
                throw new IOException("Symbolic links are not allowed in transformation paths: " + relative);
        }
    }

    private static void validateChange(TextReplacement change) throws IOException {
        if (change == null) throw new IOException("Transformation entry cannot be null");
        if (change.expectedText() == null || change.expectedText().isEmpty())
            throw new IOException("expectedText cannot be empty");
        if (change.replacementText() == null)
            throw new IOException("replacementText is required");
        if (change.expectedOccurrences() < 1)
            throw new IOException("expectedOccurrences must be at least one");
        if (change.expectedSha256() != null && !change.expectedSha256().matches("(?i)[0-9a-f]{64}"))
            throw new IOException("expectedSha256 must contain exactly 64 hexadecimal characters");
        safeRelative(change.relativePath());
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".aegis-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static int count(String text, String token) {
        int count = 0, from = 0, index;
        while ((index = text.indexOf(token, from)) >= 0) {
            count++;
            from = index + token.length();
        }
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
        public TransformationResult { applied = List.copyOf(applied); }
    }
    private record RestoreOperation(Path target, Path backup) {}
}

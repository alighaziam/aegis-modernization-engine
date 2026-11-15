package io.aegis.intake;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Objects;

/** Deterministic, offline intake. It never executes artifacts or calls a model service. */
public final class IntakeService {
    private static final long MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L;
    private final ObjectMapper mapper;
    private final Path artifactStore;

    public IntakeService(Path artifactStore) {
        this.artifactStore = Objects.requireNonNull(artifactStore).toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public IntakeReport ingest(Path intakeFile) throws IOException {
        Path source = intakeFile.toAbsolutePath().normalize();
        IntakeRequest request = readRequest(source);
        Path base = source.getParent();
        Path baseReal = base.toRealPath();
        Files.createDirectories(artifactStore.resolve("objects"));
        Files.createDirectories(artifactStore.resolve("metadata"));

        List\u003cArtifactResult\u003e results = new ArrayList\u003c\u003e();
        List\u003cString\u003e warnings = new ArrayList\u003c\u003e();
        for (ArtifactDescriptor descriptor : request.intake().artifacts()) {
            if (descriptor.location() == null || descriptor.location().isBlank()) {
                results.add(new ArtifactResult(descriptor.artifactId(), "MISSING_LOCATION", null, 0));
                warnings.add("Artifact has no location: " + descriptor.artifactId());
                continue;
            }
            Path candidate = Path.of(descriptor.location());
            Path resolved = (candidate.isAbsolute() ? candidate : base.resolve(candidate)).toAbsolutePath().normalize();
            if (!resolved.startsWith(base)) throw new IntakeException("Artifact path escapes intake directory: " + descriptor.artifactId());
            if (!Files.isRegularFile(resolved)) {
                results.add(new ArtifactResult(descriptor.artifactId(), "NOT_PRESENT_YET", null, 0));
                warnings.add("Artifact referenced but not supplied yet: " + descriptor.artifactId());
                continue;
            }
            Path realArtifact = resolved.toRealPath();
            if (!realArtifact.startsWith(baseReal)) throw new IntakeException("Artifact symlink escapes intake directory: " + descriptor.artifactId());
            long size = Files.size(realArtifact);
            if (size > MAX_ARTIFACT_BYTES) throw new IntakeException("Artifact exceeds 100 MiB limit: " + descriptor.artifactId());
            String digest = sha256(realArtifact);
            if (descriptor.sizeBytes() != null && descriptor.sizeBytes() != size)
                throw new IntakeException("Size mismatch for artifact: " + descriptor.artifactId());
            if (descriptor.sha256() != null && !descriptor.sha256().equalsIgnoreCase(digest)) {
                throw new IntakeException("SHA-256 mismatch for artifact: " + descriptor.artifactId());
            }
            Path stored = artifactStore.resolve("objects").resolve(digest);
            if (!Files.exists(stored, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.copy(realArtifact, stored, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (FileAlreadyExistsException concurrentWrite) {
                    // Another intake may have stored the same content concurrently.
                }
            }
            if (!Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS) || !digest.equals(sha256(stored)))
                throw new IntakeException("Content-addressed object is missing, linked or corrupt: " + digest);
            results.add(new ArtifactResult(descriptor.artifactId(), "REGISTERED", digest, size));
        }

        IntakeReport report = new IntakeReport(request.schemaVersion(), request.project().id(),
                request.project().name(), request.intake().batchId(), request.intake().mode(),
                results, warnings, results.stream().filter(r -> r.status().equals("REGISTERED")).count());
        Path reportPath = artifactStore.resolve("metadata").resolve(safeName(request.project().id()) + "-" + safeName(request.intake().batchId()) + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        return report;
    }

    public static IntakeRequest readRequest(Path intakeFile) throws IOException {
        Path source = intakeFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IntakeException("Intake file does not exist: " + source);
        ObjectMapper reader = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        IntakeRequest request = reader.readValue(source.toFile(), IntakeRequest.class);
        validate(request);
        return request;
    }

    private static void validate(IntakeRequest r) {
        if (r == null || !"1.0".equals(r.schemaVersion())) throw new IntakeException("Unsupported schemaVersion; expected 1.0");
        if (r.project() == null || blank(r.project().id()) || blank(r.project().name()))
            throw new IntakeException("project.id and project.name are required");
        if (!r.project().id().matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))
            throw new IntakeException("project.id must use letters, digits, dot, underscore, colon or hyphen");
        if (r.project().name().length() > 200)
            throw new IntakeException("project.name exceeds 200 characters");
        if (r.intake() == null || blank(r.intake().batchId()) || r.intake().artifacts() == null || r.intake().artifacts().isEmpty())
            throw new IntakeException("intake.batchId and at least one artifact are required");
        if (r.intake().batchId().length() > 128)
            throw new IntakeException("intake.batchId exceeds 128 characters");
        String mode = r.intake().mode();
        if (mode == null || !List.of("single", "pair", "bundle", "incremental", "manual").contains(mode)) throw new IntakeException("Unsupported intake mode: " + mode);
        if ("single".equals(mode) && r.intake().artifacts().size() != 1) throw new IntakeException("single mode requires exactly one artifact");
        if ("pair".equals(mode) && r.intake().artifacts().size() != 2) throw new IntakeException("pair mode requires exactly two artifacts");
        Set<String> artifactIds = new HashSet<>();
        for (ArtifactDescriptor a : r.intake().artifacts()) {
            if (a == null || blank(a.artifactId()) || blank(a.category()) || blank(a.format()) || blank(a.origin()))
                throw new IntakeException("Every artifact requires artifactId, category, format and origin");
            if (a.artifactId().length() > 128 || a.format().length() > 64)
                throw new IntakeException("artifactId or format exceeds its schema limit: " + a.artifactId());
            if (!List.of("document", "data_model", "database_metadata", "source_code", "build",
                    "configuration", "api_contract", "test", "infrastructure", "log", "manual_input", "other")
                    .contains(a.category()))
                throw new IntakeException("Unsupported artifact category: " + a.category());
            if (!List.of("upload", "local_path", "git_snapshot", "database_connection", "manual", "generated")
                    .contains(a.origin()))
                throw new IntakeException("Unsupported artifact origin: " + a.origin());
            if (!artifactIds.add(a.artifactId()))
                throw new IntakeException("Duplicate artifactId: " + a.artifactId());
            if (a.sha256() != null && !a.sha256().matches("(?i)[a-f0-9]{64}"))
                throw new IntakeException("Invalid SHA-256 value for artifact: " + a.artifactId());
            if (a.sizeBytes() != null && a.sizeBytes() < 0)
                throw new IntakeException("Artifact sizeBytes cannot be negative: " + a.artifactId());
            if (a.location() != null && a.location().length() > 2048)
                throw new IntakeException("Artifact location exceeds 2048 characters: " + a.artifactId());
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String safeName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.equals(value)) return safe;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return safe + "-" + HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record IntakeRequest(String schemaVersion, ProjectInput project, IntakeBatch intake,
                                TechnologySection technology, Object constraints,
                                List\u003cObject\u003e qualityAttributes, List\u003cObject\u003e confirmedFacts) {}
    public record ProjectInput(String id, String name, String description, String businessDomain, List\u003cString\u003e tags) {}
    public record IntakeBatch(String batchId, String mode, String createdAt, String previousBatchId,
                              List\u003cArtifactDescriptor\u003e artifacts) {}
    public record ArtifactDescriptor(String artifactId, String category, String format, String origin,
                                     String location, String sha256, Long sizeBytes, String mediaType,
                                     String parserHint, Object metadata) {}
    public record TechnologySection(TechnologyProfile source, TechnologyProfile target) {}
    public record TechnologyProfile(List\u003cTechnologyItem\u003e languages, List\u003cTechnologyItem\u003e frameworks,
                                    List\u003cTechnologyItem\u003e databases, List\u003cTechnologyItem\u003e platforms,
                                    List\u003cTechnologyItem\u003e buildTools) {}
    public record TechnologyItem(String name, String version, String role) {}
    public record ArtifactResult(String artifactId, String status, String sha256, long sizeBytes) {}
    public record IntakeReport(String schemaVersion, String projectId, String projectName, String batchId,
                               String mode, List\u003cArtifactResult\u003e artifacts, List\u003cString\u003e warnings,
                               long registeredCount) {}

    public static final class IntakeException extends RuntimeException {
        public IntakeException(String message) { super(message); }
    }
}

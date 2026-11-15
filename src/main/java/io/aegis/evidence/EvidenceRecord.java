package io.aegis.evidence;

import java.time.Instant;
import java.util.List;

/** Provenance attached to each observed, user-confirmed or inferred UPKM fact. */
public record EvidenceRecord(String evidenceId, String subjectRef, Kind kind, Source source,
                             Method method, Confidence confidence, ReviewStatus reviewStatus,
                             String analysisRunId, Instant createdAt, List\u003cString\u003e supersedesEvidenceIds,
                             String notes) {
    public EvidenceRecord {
        required(evidenceId, "evidenceId"); required(subjectRef, "subjectRef");
        if (kind == null || source == null || method == null || confidence == null || reviewStatus == null)
            throw new IllegalArgumentException("kind, source, method, confidence and reviewStatus are required");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        supersedesEvidenceIds = supersedesEvidenceIds == null ? List.of() : List.copyOf(supersedesEvidenceIds);
        if (kind == Kind.USER_CONFIRMED && reviewStatus != ReviewStatus.CONFIRMED && reviewStatus != ReviewStatus.SUPERSEDED)
            throw new IllegalArgumentException("user-confirmed evidence must have CONFIRMED or SUPERSEDED review status");
    }
    public enum Kind { DIRECT, USER_CONFIRMED, INFERRED }
    public enum ReviewStatus { NOT_REQUIRED, PENDING, CONFIRMED, REJECTED, SUPERSEDED }
    public enum ConfidenceLevel { HIGH, MEDIUM, LOW, NOT_APPLICABLE }
    public record Source(String sourceType, String artifactId, String displayName, Locator locator) {
        public Source { required(sourceType, "source.sourceType"); }
    }
    public record Locator(String path, Integer lineStart, Integer lineEnd, Integer columnStart,
                          Integer columnEnd, String jsonPointer, String databaseObject, Integer page, String section) {}
    public record Method(String extractorId, String extractorVersion, String ruleId, String ruleVersion, String description) {
        public Method { required(extractorId, "method.extractorId"); required(extractorVersion, "method.extractorVersion"); }
    }
    public record Confidence(ConfidenceLevel level, String rationale) {
        public Confidence { if (level == null) throw new IllegalArgumentException("confidence.level is required"); required(rationale, "confidence.rationale"); }
    }
    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}

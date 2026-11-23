package io.aegis.reporting;

import io.aegis.parser.sql.SqlDdlParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts SQL DDL parser diagnostics into SARIF 2.1.0 for code-scanning integrations. */
public final class SarifReportGenerator {

    private static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String REPOSITORY_URI = "https://github.com/alighaziam/aegis-modernization-engine";

    public Map<String, Object> generateDdlReport(SqlDdlParser.ParseResult parseResult, String sourceUri) {
        if (parseResult == null) {
            throw new IllegalArgumentException("parseResult is required");
        }
        String normalizedSource = normalizeSourceUri(sourceUri);
        Map<String, Integer> ruleIndexes = new LinkedHashMap<>();
        List<Map<String, Object>> rules = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();

        for (SqlDdlParser.Diagnostic diagnostic : parseResult.diagnostics()) {
            String ruleId = nonBlank(diagnostic.code(), "AEGIS_DDL_REVIEW");
            if (!ruleIndexes.containsKey(ruleId)) {
                ruleIndexes.put(ruleId, rules.size());
                rules.add(rule(ruleId, diagnostic.message()));
            }
            String action = nonBlank(diagnostic.action(), "REVIEW_REQUIRED");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", ruleId);
            result.put("ruleIndex", ruleIndexes.get(ruleId));
            result.put("level", level(action));
            result.put("message", Map.of("text", nonBlank(diagnostic.message(), "Aegis reported a DDL diagnostic.")));
            if (diagnostic.line() > 0) {
                Map<String, Object> artifactLocation = Map.of("uri", normalizedSource);
                Map<String, Object> physicalLocation = Map.of(
                        "artifactLocation", artifactLocation,
                        "region", Map.of("startLine", diagnostic.line()));
                result.put("locations", List.of(Map.of("physicalLocation", physicalLocation)));
            }
            result.put("partialFingerprints", Map.of(
                    "primaryLocationLineHash",
                    fingerprint(ruleId + "|" + normalizedSource + "|" + diagnostic.line() + "|" + diagnostic.message())));
            result.put("properties", Map.of("aegisAction", action));
            results.add(result);
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "Aegis Modernization Engine");
        driver.put("semanticVersion", "0.1.0");
        driver.put("informationUri", REPOSITORY_URI);
        driver.put("rules", rules);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("columnKind", "utf16CodeUnits");
        run.put("results", results);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("$schema", SARIF_SCHEMA);
        report.put("version", "2.1.0");
        report.put("runs", List.of(run));
        return report;
    }

    private static Map<String, Object> rule(String ruleId, String description) {
        String summary = nonBlank(description, "SQL DDL requires review.");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", ruleId);
        rule.put("name", ruleId);
        rule.put("shortDescription", Map.of("text", summary));
        rule.put("fullDescription", Map.of("text", "Aegis SQL DDL analysis rule: " + summary));
        rule.put("helpUri", REPOSITORY_URI + "/blob/main/docs/Persistence-and-Database-Portability.md");
        return rule;
    }

    private static String level(String action) {
        String normalized = action.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("ERROR") || normalized.contains("BLOCK")) {
            return "error";
        }
        if (normalized.contains("REVIEW") || normalized.contains("UNSUPPORTED") || normalized.contains("MANUAL")) {
            return "warning";
        }
        return "note";
    }

    private static String normalizeSourceUri(String sourceUri) {
        String value = nonBlank(sourceUri, "schema.sql").replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.startsWith("/") || value.matches("^[A-Za-z]:/.*")) {
            return value.substring(value.lastIndexOf('/') + 1);
        }
        return value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

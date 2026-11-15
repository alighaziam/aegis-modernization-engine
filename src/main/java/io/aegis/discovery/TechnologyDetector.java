package io.aegis.discovery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic technology/version discovery from build descriptors and source markers. */
public final class TechnologyDetector {
    private static final Pattern JAVA_RELEASE = Pattern.compile("(?im)(?:<maven\\.compiler\\.(?:release|source)>|<java\\.version>|sourceCompatibility\\s*=\\s*|JavaVersion\\.VERSION_)(\\d{1,2})");
    private static final Pattern BOOT_PARENT = Pattern.compile("(?is)<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern BOOT_PLUGIN = Pattern.compile("(?im)(?:org\\.springframework\\.boot:spring-boot-gradle-plugin:|springBootVersion\\s*=\\s*['\"])([0-9][^'\"]*)");
    private static final Pattern DEPENDENCY = Pattern.compile("(?im)(?:<artifactId>(spring-[^<]+)</artifactId>\\s*<version>([^<]+)</version>|(spring-[A-Za-z0-9-]+):([^:'\\s]+):([0-9][^'\\s]*))");

    public DetectionReport detect(Map<String, String> files) {
        if (files == null) throw new IllegalArgumentException("files are required");
        Map<String, TechnologyFinding> findings = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String path = entry.getKey().replace('\\', '/');
            String content = entry.getValue() == null ? "" : entry.getValue();
            String lower = path.toLowerCase();
            if (lower.endsWith("pom.xml") || lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts")) {
                Matcher java = JAVA_RELEASE.matcher(content);
                if (java.find()) add(findings, "Java", normalizeJava(java.group(1)), "runtime", path, "high");
                Matcher parent = BOOT_PARENT.matcher(content);
                if (parent.find()) add(findings, "Spring Boot", parent.group(1).trim(), "framework", path, "high");
                Matcher plugin = BOOT_PLUGIN.matcher(content);
                if (plugin.find()) add(findings, "Spring Boot", plugin.group(1).trim(), "framework", path, "high");
                Matcher deps = DEPENDENCY.matcher(content);
                while (deps.find()) {
                    String name = deps.group(1) != null ? deps.group(1) : deps.group(3);
                    String version = deps.group(2) != null ? deps.group(2) : deps.group(5);
                    if (name != null) add(findings, title(name), version == null ? null : version.trim(), "library", path, version == null ? "medium" : "high");
                }
            }
            if (lower.endsWith(".java")) {
                if (content.contains("@SpringBootApplication")) add(findings, "Spring Boot", null, "framework", path, "medium");
                if (content.contains("org.springframework.")) add(findings, "Spring Framework", null, "framework", path, "medium");
                if (content.contains("jakarta.persistence.")) add(findings, "Jakarta Persistence", null, "orm", path, "medium");
                else if (content.contains("javax.persistence.")) add(findings, "Java Persistence API", null, "orm", path, "medium");
            }
        });
        if (findings.isEmpty()) diagnostics.add("No supported technology markers were found; provide build descriptors or source files.");
        return new DetectionReport(List.copyOf(findings.values()), List.copyOf(diagnostics));
    }

    private static void add(Map<String, TechnologyFinding> map, String name, String version,
                            String role, String source, String confidence) {
        String key = name.toLowerCase();
        TechnologyFinding old = map.get(key);
        if (old == null || (old.version() == null && version != null))
            map.put(key, new TechnologyFinding(name, version, role, source, confidence));
    }
    private static String normalizeJava(String value) { return value.equals("1") ? "1.x" : value; }
    private static String title(String artifact) {
        String value = artifact.replace("spring-boot-starter-", "").replace("spring-", "Spring ");
        if (value.equals(artifact)) return artifact;
        return value.equals("Spring boot") ? "Spring Boot" : "Spring " + value;
    }

    public record TechnologyFinding(String name, String version, String role,
                                    String evidenceSource, String confidence) {}
    public record DetectionReport(List<TechnologyFinding> findings, List<String> diagnostics) {}
}

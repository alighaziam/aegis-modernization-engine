package io.aegis.analysis;

import io.aegis.domain.ProjectKnowledgeModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Compares explicit Spring controller routes with OpenAPI endpoint inventory. */
public final class ApiContractConsistencyAnalyzer {
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/{}]+}");

    public ConsistencyReport analyze(SpringApiAnalyzer.Analysis spring,
                                     List<ProjectKnowledgeModel.ApiEndpoint> openApiEndpoints) {
        if (spring == null || openApiEndpoints == null)
            throw new IllegalArgumentException("Spring route analysis and OpenAPI endpoint inventory are required");
        List<Finding> findings = new ArrayList<>();
        if (spring.endpoints().isEmpty() || openApiEndpoints.isEmpty()) {
            findings.add(new Finding("API_CONTRACT_COMPARISON_NOT_AVAILABLE", Severity.INFO,
                    null, null, null, "Both Spring route mappings and OpenAPI endpoints are required for comparison."));
            return new ConsistencyReport(false, 0, 0, 0, 0, findings,
                    List.of("The comparison is skipped when either source or contract endpoint inventory is empty.",
                            "Only explicit HTTP method and path pairs are compared."));
        }

        Set<String> duplicateSource = duplicates(spring.endpoints().stream()
                .map(endpoint -> key(endpoint.httpMethod(), endpoint.path())).toList());
        for (String duplicate : duplicateSource)
            findings.add(new Finding("DUPLICATE_SPRING_ROUTE", Severity.WARNING, duplicate, null, null,
                    "Multiple controller methods expose the same HTTP method and normalized path."));

        Set<String> duplicateSpecs = duplicates(openApiEndpoints.stream()
                .map(endpoint -> key(endpoint.method(), endpoint.path())).toList());
        for (String duplicate : duplicateSpecs)
            findings.add(new Finding("DUPLICATE_OPENAPI_OPERATION", Severity.WARNING, null, duplicate, null,
                    "Multiple OpenAPI operations declare the same HTTP method and normalized path."));

        int matched = 0, sourceOnly = 0, contractOnly = 0;
        for (SpringApiAnalyzer.EndpointMapping source : spring.endpoints()) {
            List<ProjectKnowledgeModel.ApiEndpoint> samePath = openApiEndpoints.stream()
                    .filter(spec -> normalizePath(spec.path()).equals(normalizePath(source.path()))).toList();
            List<ProjectKnowledgeModel.ApiEndpoint> exact = samePath.stream()
                    .filter(spec -> methodMatches(source.httpMethod(), spec.method())).toList();
            if (!exact.isEmpty()) {
                matched++;
                findings.add(new Finding("API_ROUTE_MATCHED", Severity.INFO, source.path(),
                        exact.getFirst().path(), source.httpMethod(),
                        "Spring route matches an OpenAPI method/path contract."));
            } else if (!samePath.isEmpty()) {
                sourceOnly++;
                findings.add(new Finding("API_METHOD_MISMATCH", Severity.WARNING, source.path(),
                        samePath.getFirst().path(), source.httpMethod(),
                        "Spring and OpenAPI declare the same path with different HTTP methods."));
            } else {
                sourceOnly++;
                findings.add(new Finding("SPRING_ENDPOINT_NOT_IN_OPENAPI", Severity.WARNING, source.path(),
                        null, source.httpMethod(), "Spring controller route has no matching OpenAPI operation."));
            }
        }
        for (ProjectKnowledgeModel.ApiEndpoint spec : openApiEndpoints) {
            boolean found = spring.endpoints().stream().anyMatch(source ->
                    normalizePath(spec.path()).equals(normalizePath(source.path()))
                            && methodMatches(source.httpMethod(), spec.method()));
            if (!found) {
                contractOnly++;
                findings.add(new Finding("OPENAPI_ENDPOINT_NOT_IN_SPRING_SOURCE", Severity.WARNING,
                        null, spec.path(), spec.method(),
                        "OpenAPI operation has no matching explicit Spring controller route."));
            }
        }
        return new ConsistencyReport(true, matched, sourceOnly, contractOnly,
                duplicateSource.size() + duplicateSpecs.size(), findings,
                List.of("Path variables are normalized by placeholder position; path case remains significant.",
                        "ANY RequestMapping methods match any documented HTTP method.",
                        "Composed annotations, inherited/interface mappings, route conditions and runtime registration are not resolved.",
                        "Request/response schemas and parameter compatibility are not compared."));
    }

    private static boolean methodMatches(String source, String contract) {
        return "ANY".equalsIgnoreCase(source) || source.equalsIgnoreCase(contract);
    }

    private static String key(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + normalizePath(path);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = PATH_VARIABLE.matcher(path.trim().replaceAll("/{2,}", "/")).replaceAll("{}");
        if (normalized.length() > 1 && normalized.endsWith("/"))
            normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static Set<String> duplicates(List<String> values) {
        Set<String> seen = new HashSet<>(), duplicates = new java.util.TreeSet<>();
        for (String value : values) if (!seen.add(value)) duplicates.add(value);
        return duplicates;
    }

    public enum Severity { INFO, WARNING }
    public record Finding(String code, Severity severity, String springPath,
                          String openApiPath, String method, String message) {}
    public record ConsistencyReport(boolean available, int matched, int springOnly,
                                    int openApiOnly, int duplicateRoutes,
                                    List<Finding> findings, List<String> limitations) {
        public ConsistencyReport {
            findings = List.copyOf(findings);
            limitations = List.copyOf(limitations);
        }
        public long warningCount() { return findings.stream().filter(f -> f.severity() == Severity.WARNING).count(); }
    }
}

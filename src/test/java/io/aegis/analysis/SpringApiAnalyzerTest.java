package io.aegis.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SpringApiAnalyzerTest {
    @TempDir Path temp;

    @Test
    void extractsRestControllerClassAndMethodMappings() {
        String source = """
                package com.acme.api;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                @RestController
                @RequestMapping("/api/customers")
                class CustomerController {
                    @GetMapping("/{id}")
                    Customer find() { return null; }
                    @PostMapping
                    Customer create() { return null; }
                }
                """;

        var result = new SpringApiAnalyzer().parse(source, "CustomerController.java");

        assertEquals(2, result.endpoints().size());
        assertTrue(result.endpoints().stream().anyMatch(e ->
                e.httpMethod().equals("GET") && e.path().equals("/api/customers/{id}")));
        assertTrue(result.endpoints().stream().anyMatch(e ->
                e.httpMethod().equals("POST") && e.path().equals("/api/customers")));
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void extractsRequestMappingMethodsAndControllerResponseBodyMethods() {
        String source = """
                package com.acme.api;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.ResponseBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                @Controller
                class StatusController {
                    @ResponseBody
                    @RequestMapping(value = "/status", method = RequestMethod.GET)
                    String status() { return "ok"; }
                }
                """;

        var result = new SpringApiAnalyzer().parse(source, "StatusController.java");

        assertEquals(1, result.endpoints().size());
        assertEquals("GET", result.endpoints().getFirst().httpMethod());
        assertEquals("/status", result.endpoints().getFirst().path());
    }

    @Test
    void handlesMultiplePathsAndMultipleRequestMethods() {
        String source = """
                package com.acme.api;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping(path = {"/v1", "/v2"})
                class VersionedController {
                    @RequestMapping(path = "/health", method = {RequestMethod.GET, RequestMethod.HEAD})
                    String health() { return "ok"; }
                }
                """;

        var result = new SpringApiAnalyzer().parse(source, "VersionedController.java");

        assertEquals(4, result.endpoints().size());
        assertEquals(4, result.endpoints().stream().map(e -> e.httpMethod() + " " + e.path()).distinct().count());
    }

    @Test
    void scansNestedJavaFilesAndSkipsGeneratedDirectories() throws Exception {
        Files.createDirectories(temp.resolve("src/main/java/com/acme"));
        Files.createDirectories(temp.resolve("target/generated"));
        Files.writeString(temp.resolve("src/main/java/com/acme/Controller.java"), """
                package com.acme;
                import org.springframework.web.bind.annotation.*;
                @RestController class Controller { @GetMapping("/ok") String ok() { return "ok"; } }
                """);
        Files.writeString(temp.resolve("target/generated/GeneratedController.java"), """
                import org.springframework.web.bind.annotation.*;
                @RestController class GeneratedController { @GetMapping("/ignored") String ok() { return "ok"; } }
                """);

        var report = new SpringApiAnalyzer().analyzeProject(temp);

        assertEquals(1, report.filesScanned());
        assertEquals(1, report.endpoints().size());
        assertEquals("/ok", report.endpoints().getFirst().path());
    }

    @Test
    void ignoresClassesWithoutSpringControllerAnnotations() {
        var result = new SpringApiAnalyzer().parse("""
                package com.acme;
                class OrdinaryService { void run() {} }
                """, "OrdinaryService.java");
        assertTrue(result.endpoints().isEmpty());
    }
}

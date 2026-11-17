package io.aegis.parser.java;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceAnalyzerTest {
    @Test
    void extractsPackagesImportsTypesAndMethodSignaturesWithoutCompilingSource() throws Exception {
        String source = """
                package com.acme.orders;
                import java.util.List;
                import static java.util.Collections.emptyList;

                public class OrderService extends BaseService implements Runnable {
                    public List<String> find(long id) { return emptyList(); }
                    @Override public void run() {}
                    static class Nested {}
                }
                """;

        JavaSourceAnalyzer.AnalysisResult result =
                new JavaSourceAnalyzer().analyze(source, "OrderService.java");

        assertEquals(1, result.files().size());
        JavaSourceAnalyzer.SourceFile file = result.files().get(0);
        assertEquals("com.acme.orders", file.packageName());
        assertEquals(2, file.imports().size());
        assertTrue(file.imports().stream().anyMatch(i -> i.isStatic()
                && i.qualifiedName().equals("java.util.Collections.emptyList")));
        assertEquals(2, file.types().size());
        JavaSourceAnalyzer.TypeDeclaration service = file.types().stream()
                .filter(t -> t.name().equals("OrderService")).findFirst().orElseThrow();
        assertEquals("BaseService", service.extendsType());
        assertEquals(List.of("Runnable"), service.implementsTypes());
        assertTrue(service.methods().stream().anyMatch(m -> m.name().equals("find")
                && m.parameterTypes().equals("long") && m.returnType().equals("List<String>")));
        assertTrue(service.line() > 0);
    }

    @Test
    void reportsSyntaxErrorsAsDiagnostics() throws Exception {
        JavaSourceAnalyzer.AnalysisResult result = new JavaSourceAnalyzer()
                .analyze("package broken; public class Broken { void x( }", "Broken.java");

        assertTrue(result.diagnostics().stream().anyMatch(d -> "ERROR".equals(d.kind())));
    }
    @Test
    void doesNotAttributeAnonymousClassMethodsToEnclosingType() throws Exception {
        JavaSourceAnalyzer.AnalysisResult result = new JavaSourceAnalyzer().analyze("""
                package com.acme;
                class Holder {
                    Object value = new Runnable() {
                        public void run() {}
                    };
                    void realMethod() {}
                }
                """, "Holder.java");

        JavaSourceAnalyzer.TypeDeclaration holder = result.files().get(0).types().stream()
                .filter(type -> type.name().equals("Holder")).findFirst().orElseThrow();
        assertTrue(holder.methods().stream().anyMatch(method -> method.name().equals("realMethod")));
        assertFalse(holder.methods().stream().anyMatch(method -> method.name().equals("run")));
    }

    @Test
    void extractsDeclaredFieldsWithoutTreatingLocalVariablesAsFields() throws Exception {
        var result = new JavaSourceAnalyzer().analyze("""
                package com.acme;
                class Holder {
                    private Customer customer;
                    void load() { Customer local = null; }
                }
                """, "Holder.java");
        var holder = result.files().get(0).types().stream()
                .filter(type -> type.name().equals("Holder")).findFirst().orElseThrow();
        assertTrue(holder.fields().stream().anyMatch(field ->
                field.name().equals("customer") && field.type().equals("Customer")));
        assertFalse(holder.fields().stream().anyMatch(field -> field.name().equals("local")));
    }

}

package io.aegis.transformation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaImportMigrationEngineTest {
    private final JavaImportMigrationEngine engine = new JavaImportMigrationEngine();

    @Test void migratesOnlyAllowlistedImportsUsingParsedImportNodes() throws Exception {
        String source = """
                package demo;
                import javax.persistence.Entity;
                import javax.persistence.*;
                import javax.validation.Valid;
                import javax.annotation.PostConstruct;
                import static javax.persistence.GenerationType.IDENTITY;
                class Customer {}
                """;
        var result = engine.migrate(source, "Customer.java");
        assertTrue(result.safeToReview());
        assertEquals(3, result.replacements().size());
        assertTrue(result.transformedSource().contains("import jakarta.persistence.Entity;"));
        assertTrue(result.transformedSource().contains("import jakarta.persistence.*;"));
        assertTrue(result.transformedSource().contains("import jakarta.validation.Valid;"));
        assertTrue(result.transformedSource().contains("import javax.annotation.PostConstruct;"));
        assertTrue(result.transformedSource().contains("import static javax.persistence.GenerationType.IDENTITY;"));
    }

    @Test void leavesUnrelatedAndLookalikePackagesUntouched() throws Exception {
        String source = "import javax.persistenceX.Entity;\nimport javax.sql.DataSource;\nclass A {}";
        var result = engine.migrate(source, "A.java");
        assertTrue(result.replacements().isEmpty());
        assertEquals(source, result.transformedSource());
    }

    @Test void refusesToProduceChangesForInvalidJavaSyntax() throws Exception {
        String source = "import javax.persistence.Entity; class Broken {";
        var result = engine.migrate(source, "Broken.java");
        assertFalse(result.safeToReview());
        assertEquals(source, result.transformedSource());
        assertTrue(result.replacements().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> "ERROR".equals(d.kind())));
    }

    @Test void preservesImportFormattingAndComments() throws Exception {
        String source = "import   /* keep javax.persistence.Entity */ javax.persistence.Entity;\nclass A {}";
        var result = engine.migrate(source, "A.java");
        assertTrue(result.safeToReview());
        assertTrue(result.transformedSource().contains(
                "import   /* keep javax.persistence.Entity */ jakarta.persistence.Entity;"));
    }

    @Test void isIdempotent() throws Exception {
        String source = "import javax.servlet.http.HttpServlet; class A {}";
        String once = engine.migrate(source, "A.java").transformedSource();
        var twice = engine.migrate(once, "A.java");
        assertEquals(once, twice.transformedSource());
        assertTrue(twice.replacements().isEmpty());
    }
}

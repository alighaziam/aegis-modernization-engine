package io.aegis.transformation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaImportMigratorTest {
    private final JavaImportMigrator migrator = new JavaImportMigrator();

    @Test
    void migratesOnlyAllowlistedImportsUsingSyntaxTree() {
        String source = """
                package sample;
                import javax.persistence.Entity;
                import javax.persistence.*;
                import javax.validation.constraints.NotNull;
                import javax.crypto.Cipher;
                class Example {
                    String text = "javax.persistence.Entity";
                }
                """;

        var plan = migrator.plan(source, "Example.java");

        assertTrue(plan.safeToWrite());
        assertEquals(3, plan.appliedRules().size());
        assertTrue(plan.migratedSource().contains("import jakarta.persistence.Entity;"));
        assertTrue(plan.migratedSource().contains("import jakarta.persistence.*;"));
        assertTrue(plan.migratedSource().contains("import jakarta.validation.constraints.NotNull;"));
        assertTrue(plan.migratedSource().contains("import javax.crypto.Cipher;"));
        assertTrue(plan.migratedSource().contains("\"javax.persistence.Entity\""));
    }

    @Test
    void refusesToWriteWhenJavaSyntaxHasErrors() {
        var plan = migrator.plan("import javax.persistence.Entity; class Broken {", "Broken.java");
        assertFalse(plan.safeToWrite());
        assertFalse(plan.changed());
        assertEquals("JAVA_PARSE_ERRORS", plan.diagnostics().getFirst().code());
    }

    @Test
    void leavesSourceUnchangedWhenNoRuleMatches() {
        String source = "package sample; import java.util.List; class Example {}";
        var plan = migrator.plan(source, "Example.java");
        assertTrue(plan.safeToWrite());
        assertFalse(plan.changed());
        assertEquals(source, plan.migratedSource());
    }
}

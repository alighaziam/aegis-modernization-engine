package io.aegis.transformation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TransformationEngineTest {
    @TempDir Path temp;
    @Test void appliesPreconditionedReplacementAndCanRollback() throws Exception {
        Path root=temp.resolve("project"), rollback=temp.resolve("rollback");
        Files.createDirectories(root);
        Files.writeString(root.resolve("config.txt"),"java=8\n");
        var engine=new TransformationEngine();
        var result=engine.apply(root,rollback,List.of(new TransformationEngine.TextReplacement(
                "config.txt","java=8","java=25",1,null)));
        assertEquals("java=25\n",Files.readString(root.resolve("config.txt")));
        engine.rollback(result,root);
        assertEquals("java=8\n",Files.readString(root.resolve("config.txt")));
    }
    @Test
    void rollsBackEarlierChangesWhenALaterPreconditionFails() throws Exception {
        Path root = temp.resolve("transaction-project");
        Path rollback = temp.resolve("transaction-rollback");
        Files.createDirectories(root);
        Files.writeString(root.resolve("a.txt"), "before-a");
        Files.writeString(root.resolve("b.txt"), "before-b");

        assertThrows(java.io.IOException.class, () -> new TransformationEngine().apply(root, rollback, List.of(
                new TransformationEngine.TextReplacement("a.txt", "before-a", "after-a", 1, null),
                new TransformationEngine.TextReplacement("b.txt", "missing", "after-b", 1, null))));

        assertEquals("before-a", Files.readString(root.resolve("a.txt")));
        assertEquals("before-b", Files.readString(root.resolve("b.txt")));
    }

    @Test
    void rollbackPreflightsAllFilesBeforeRestoringAnyFile() throws Exception {
        Path root = temp.resolve("preflight-project"), rollback = temp.resolve("preflight-rollback");
        Files.createDirectories(root);
        Files.writeString(root.resolve("a.txt"), "before-a");
        Files.writeString(root.resolve("b.txt"), "before-b");
        var result = new TransformationEngine().apply(root, rollback, List.of(
                new TransformationEngine.TextReplacement("a.txt", "before-a", "after-a", 1, null),
                new TransformationEngine.TextReplacement("b.txt", "before-b", "after-b", 1, null)));
        Files.writeString(root.resolve("b.txt"), "changed-after-transform");

        assertThrows(java.io.IOException.class, () -> new TransformationEngine().rollback(result, root));
        assertEquals("after-a", Files.readString(root.resolve("a.txt")));
        assertEquals("changed-after-transform", Files.readString(root.resolve("b.txt")));
    }

    @Test
    void rejectsRollbackStorageInsideProjectTreeAndExistingBackupCollisions() throws Exception {
        Path root = temp.resolve("safe-project"), rollback = temp.resolve("safe-rollback");
        Files.createDirectories(root);
        Files.writeString(root.resolve("config.txt"), "java=8");
        assertThrows(java.io.IOException.class, () -> new TransformationEngine().apply(
                root, root.resolve("rollback"), List.of(
                        new TransformationEngine.TextReplacement("config.txt", "java=8", "java=25", 1, null))));

        Files.createDirectories(rollback);
        Files.writeString(rollback.resolve("config.txt"), "do-not-overwrite");
        assertThrows(java.io.IOException.class, () -> new TransformationEngine().apply(
                root, rollback, List.of(
                        new TransformationEngine.TextReplacement("config.txt", "java=8", "java=25", 1, null))));
        assertEquals("java=8", Files.readString(root.resolve("config.txt")));
        assertEquals("do-not-overwrite", Files.readString(rollback.resolve("config.txt")));
    }

}

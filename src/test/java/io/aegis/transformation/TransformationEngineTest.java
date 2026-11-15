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
}

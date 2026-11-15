package io.aegis.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ValidationEngineTest {
    @TempDir Path temp;
    @Test void validatesGeneratedArtifactHash() throws Exception {
        Path file=temp.resolve("out.txt");
        byte[] bytes="generated".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file,bytes);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var report=new ValidationEngine().validate(temp,List.of(new ValidationEngine.ExpectedArtifact("out.txt",hash)));
        assertTrue(report.valid());
    }
}

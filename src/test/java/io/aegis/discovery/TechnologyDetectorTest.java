package io.aegis.discovery;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TechnologyDetectorTest {
    @Test void detectsJavaAndSpringBootVersionsFromMaven() {
        var report = new TechnologyDetector().detect(Map.of("pom.xml", """
                <project><properties><java.version>21</java.version></properties>
                <parent><artifactId>spring-boot-starter-parent</artifactId><version>3.4.2</version></parent></project>
                """));
        assertTrue(report.findings().stream().anyMatch(f -> f.name().equals("Java") && "21".equals(f.version())));
        assertTrue(report.findings().stream().anyMatch(f -> f.name().equals("Spring Boot") && "3.4.2".equals(f.version())));
    }
}

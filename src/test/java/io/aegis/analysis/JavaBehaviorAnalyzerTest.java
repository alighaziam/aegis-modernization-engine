package io.aegis.analysis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaBehaviorAnalyzerTest {
    @Test void extractsCallsGuardsAndThrownExceptionsFromAst() {
        String source = """
                class OrderService {
                    void place(int quantity) {
                        if (quantity < 1) {
                            throw new IllegalArgumentException("quantity");
                        }
                        repository.save(quantity);
                    }
                }
                """;
        var report = new JavaBehaviorAnalyzer().analyze(source, "OrderService.java");
        assertEquals(1, report.methodCalls().size());
        assertEquals("repository", report.methodCalls().get(0).receiverCandidate());
        assertEquals(1, report.guards().size());
        assertEquals(1, report.thrownExceptions().size());
    }
}

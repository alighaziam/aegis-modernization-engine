package io.aegis.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JpaMappingAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void extractsEntityTableColumnsAndExplicitRelationshipJoinColumns() throws Exception {
        Path orders = tempDir.resolve("com/acme/Order.java");
        Path customers = tempDir.resolve("com/acme/Customer.java");
        Files.createDirectories(orders.getParent());
        Files.writeString(customers, """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.Id;
                import jakarta.persistence.Column;
                @Entity
                @Table(name = "CUSTOMERS", schema = "SALES")
                public class Customer {
                    @Id
                    @Column(name = "CUSTOMER_ID", nullable = false)
                    private Long id;
                }
                """);
        Files.writeString(orders, """
                package com.acme;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.JoinColumn;
                import jakarta.persistence.Column;
                import jakarta.persistence.Id;
                @Entity
                @Table(name = "ORDERS")
                public class Order {
                    @Id private Long id;
                    @Column(name = "ORDER_TOTAL")
                    private java.math.BigDecimal total;
                    @ManyToOne
                    @JoinColumn(name = "CUSTOMER_ID", referencedColumnName = "CUSTOMER_ID", nullable = "false")
                    private Customer customer;
                }
                """);

        var report = new JpaMappingAnalyzer().analyze(tempDir);

        assertEquals(2, report.entities().size());
        var order = report.entities().stream().filter(e -> e.simpleName().equals("Order")).findFirst().orElseThrow();
        assertEquals("ORDERS", order.tableName());
        assertTrue(order.columns().stream().anyMatch(c ->
                c.fieldName().equals("total") && c.columnName().equals("ORDER_TOTAL")));
        assertEquals(1, report.relationships().size());
        var edge = report.relationships().getFirst();
        assertEquals("com.acme.Order", edge.fromEntity());
        assertEquals("com.acme.Customer", edge.toEntity());
        assertEquals("ManyToOne", edge.kind());
        assertEquals("CUSTOMER_ID", edge.joinColumns().getFirst().columnName());
        assertEquals("CUSTOMER_ID", edge.joinColumns().getFirst().referencedColumnName());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void resolvesCollectionRelationshipTargetAndReportsUnresolvedTargets() throws Exception {
        Files.writeString(tempDir.resolve("Order.java"), """
                package com.acme;
                import jakarta.persistence.*;
                import java.util.Set;
                @Entity class Order {
                    @OneToMany(mappedBy = "order")
                    private Set<LineItem> items;
                    @ManyToOne
                    private MissingCustomer customer;
                }
                """);
        Files.writeString(tempDir.resolve("LineItem.java"), """
                package com.acme;
                import jakarta.persistence.Entity;
                @Entity class LineItem {}
                """);

        var report = new JpaMappingAnalyzer().analyze(tempDir);

        assertEquals(1, report.relationships().size());
        assertEquals("com.acme.LineItem", report.relationships().getFirst().toEntity());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.startsWith("UNRESOLVED_JPA_TARGET:")));
    }

    @Test
    void ignoresUnrelatedEntityAnnotationWithoutJpaImport() throws Exception {
        Files.writeString(tempDir.resolve("NotJpa.java"), """
                package com.acme;
                @interface Entity {}
                @Entity class NotJpa {}
                """);

        var report = new JpaMappingAnalyzer().analyze(tempDir);

        assertTrue(report.entities().isEmpty());
        assertTrue(report.relationships().isEmpty());
    }
}

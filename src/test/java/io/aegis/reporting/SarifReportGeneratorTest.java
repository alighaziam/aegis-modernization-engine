package io.aegis.reporting;

import io.aegis.parser.sql.SqlDdlParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SarifReportGeneratorTest {

    private final SarifReportGenerator generator = new SarifReportGenerator();

    @Test
    void mapsDdlDiagnosticsToSarifRulesLocationsAndStableFingerprints() {
        SqlDdlParser.ParseResult parsed = new SqlDdlParser().parse(
                "ALTER TABLE missing ALTER COLUMN id SET NOT NULL;",
                "db/migration/V7__customer.sql",
                "postgresql");

        Map<String, Object> report = generator.generateDdlReport(parsed, "db/migration/V7__customer.sql");
        assertEquals("2.1.0", report.get("version"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runs = (List<Map<String, Object>>) report.get("runs");
        @SuppressWarnings("unchecked")
        Map<String, Object> run = runs.getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> tool = (Map<String, Object>) run.get("tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> driver = (Map<String, Object>) tool.get("driver");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) driver.get("rules");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) run.get("results");

        assertEquals("Aegis Modernization Engine", driver.get("name"));
        assertEquals(1, rules.size());
        assertEquals("ALTER_TABLE_BEFORE_CREATE", rules.getFirst().get("id"));
        assertEquals(1, results.size());
        Map<String, Object> result = results.getFirst();
        assertEquals("warning", result.get("level"));
        assertEquals("ALTER_TABLE_BEFORE_CREATE", result.get("ruleId"));
        assertTrue(((Map<?, ?>) result.get("partialFingerprints"))
                .get("primaryLocationLineHash").toString().matches("[a-f0-9]{64}"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locations = (List<Map<String, Object>>) result.get("locations");
        @SuppressWarnings("unchecked")
        Map<String, Object> physical = (Map<String, Object>) locations.getFirst().get("physicalLocation");
        assertEquals("db/migration/V7__customer.sql",
                ((Map<?, ?>) physical.get("artifactLocation")).get("uri"));
        assertEquals(1, ((Map<?, ?>) physical.get("region")).get("startLine"));
    }

    @Test
    void emitsValidEmptySarifRunWhenThereAreNoDiagnostics() {
        SqlDdlParser.ParseResult parsed = new SqlDdlParser().parse(
                "CREATE TABLE customer (id BIGINT PRIMARY KEY);", "schema.sql", "postgresql");
        Map<String, Object> report = generator.generateDdlReport(parsed, "/tmp/schema.sql");

        @SuppressWarnings("unchecked")
        Map<String, Object> run = ((List<Map<String, Object>>) report.get("runs")).getFirst();
        assertTrue(((List<?>) run.get("results")).isEmpty());
        assertTrue(((List<?>) ((Map<?, ?>) ((Map<?, ?>) run.get("tool")).get("driver")).get("rules")).isEmpty());
    }
}

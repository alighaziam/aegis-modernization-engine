package io.aegis.persistence.oracle;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Verifies that the complete Aegis Oracle persistence schema has been installed. */
public final class OracleSchemaVerifier {
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "AEGIS_PROJECTS", "AEGIS_ANALYSIS_RUNS", "AEGIS_ARTIFACTS",
            "AEGIS_EVIDENCE", "AEGIS_CONFLICT_EVENTS");
    private static final String QUERY = """
            SELECT TABLE_NAME FROM USER_TABLES
             WHERE TABLE_NAME IN (
               'AEGIS_PROJECTS', 'AEGIS_ANALYSIS_RUNS', 'AEGIS_ARTIFACTS',
               'AEGIS_EVIDENCE', 'AEGIS_CONFLICT_EVENTS'
             )
            """;

    public SchemaReport inspect(DataSource dataSource) {
        java.util.Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(QUERY);
             ResultSet result = statement.executeQuery()) {
            Set<String> present = new TreeSet<>();
            while (result.next()) present.add(result.getString(1).toUpperCase(java.util.Locale.ROOT));
            Set<String> missing = new TreeSet<>(REQUIRED_TABLES);
            missing.removeAll(present);
            return new SchemaReport(List.copyOf(present), List.copyOf(missing), missing.isEmpty());
        } catch (Exception e) {
            throw new io.aegis.persistence.PersistenceException("Could not inspect Oracle Aegis schema", e);
        }
    }

    public record SchemaReport(List<String> presentTables, List<String> missingTables, boolean complete) {
        public SchemaReport {
            presentTables = List.copyOf(presentTables);
            missingTables = List.copyOf(missingTables);
        }
    }
}

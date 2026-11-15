package io.aegis.persistence.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aegis.evidence.ConflictRegistry;
import io.aegis.evidence.EvidenceRecord;
import io.aegis.persistence.ConflictEventRepository;
import io.aegis.persistence.EvidenceRepository;
import io.aegis.persistence.PersistenceException;

import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Append-only Oracle persistence for provenance evidence and conflict lifecycle events. */
public final class OracleKnowledgeJournalRepository implements EvidenceRepository, ConflictEventRepository {
    private static final String INSERT_EVIDENCE = """
            INSERT INTO AEGIS_EVIDENCE
              (PROJECT_ID, EVIDENCE_ID, SUBJECT_REF, ANALYSIS_RUN_ID, KIND, REVIEW_STATUS, CREATED_AT, EVIDENCE_JSON)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EVIDENCE = """
            SELECT EVIDENCE_JSON FROM AEGIS_EVIDENCE
             WHERE PROJECT_ID = ? AND ANALYSIS_RUN_ID = ?
             ORDER BY CREATED_AT, EVIDENCE_ID
            """;
    private static final String INSERT_CONFLICT = """
            INSERT INTO AEGIS_CONFLICT_EVENTS
              (PROJECT_ID, EVENT_ID, SUBJECT_REF, EVENT_TYPE, OCCURRED_AT, EVENT_JSON)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_CONFLICTS = """
            SELECT EVENT_JSON FROM AEGIS_CONFLICT_EVENTS
             WHERE PROJECT_ID = ? ORDER BY OCCURRED_AT, EVENT_ID
            """;
    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public OracleKnowledgeJournalRepository(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
    }

    @Override
    public void append(String projectId, EvidenceRecord evidence) {
        requireProject(projectId);
        java.util.Objects.requireNonNull(evidence, "evidence");
        try {
            String json = mapper.writeValueAsString(evidence);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(INSERT_EVIDENCE)) {
                statement.setString(1, projectId);
                statement.setString(2, evidence.evidenceId());
                statement.setString(3, evidence.subjectRef());
                statement.setString(4, evidence.analysisRunId());
                statement.setString(5, evidence.kind().name());
                statement.setString(6, evidence.reviewStatus().name());
                statement.setTimestamp(7, java.sql.Timestamp.from(evidence.createdAt()));
                statement.setCharacterStream(8, new StringReader(json), json.length());
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new PersistenceException("Could not append evidence to Oracle journal", e);
        }
    }

    @Override
    public List<EvidenceRecord> findByAnalysisRun(String projectId, String analysisRunId) {
        requireProject(projectId);
        if (analysisRunId == null || analysisRunId.isBlank())
            throw new IllegalArgumentException("analysisRunId is required");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_EVIDENCE)) {
            statement.setString(1, projectId);
            statement.setString(2, analysisRunId);
            try (ResultSet result = statement.executeQuery()) {
                List<EvidenceRecord> records = new ArrayList<>();
                while (result.next()) records.add(mapper.readValue(result.getCharacterStream(1), EvidenceRecord.class));
                return List.copyOf(records);
            }
        } catch (Exception e) {
            throw new PersistenceException("Could not read evidence from Oracle journal", e);
        }
    }

    @Override
    public void append(String projectId, ConflictRegistry.ConflictEvent event) {
        requireProject(projectId);
        java.util.Objects.requireNonNull(event, "event");
        try {
            String json = mapper.writeValueAsString(event);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(INSERT_CONFLICT)) {
                statement.setString(1, projectId);
                statement.setString(2, event.eventId());
                statement.setString(3, event.subjectRef());
                statement.setString(4, event.type().name());
                statement.setTimestamp(5, java.sql.Timestamp.from(event.occurredAt()));
                statement.setCharacterStream(6, new StringReader(json), json.length());
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new PersistenceException("Could not append conflict event to Oracle journal", e);
        }
    }

    @Override
    public List<ConflictRegistry.ConflictEvent> history(String projectId) {
        requireProject(projectId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_CONFLICTS)) {
            statement.setString(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                List<ConflictRegistry.ConflictEvent> events = new ArrayList<>();
                while (result.next()) events.add(mapper.readValue(result.getCharacterStream(1), ConflictRegistry.ConflictEvent.class));
                return List.copyOf(events);
            }
        } catch (Exception e) {
            throw new PersistenceException("Could not read conflict history from Oracle journal", e);
        }
    }

    private static void requireProject(String projectId) {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
    }
}

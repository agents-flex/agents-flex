package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.event.AgentRunEvent;
import com.agentsflex.agent.event.AgentRunEventStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 使用独立序列表和唯一约束保存严格有序、幂等的 AgentRun 事件。 */
public final class JdbcAgentRunEventStore extends JdbcAgentStoreSupport implements AgentRunEventStore {
    JdbcAgentRunEventStore(JdbcAgentStoreConfig config) { super(config); }

    @Override
    public AgentRunEvent append(AgentRunEvent event) {
        if (event == null) throw new IllegalArgumentException("event must not be null");
        AgentRunEvent existing = loadById(event.getEventId());
        if (existing != null) return existing;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    existing = loadById(connection, event.getEventId());
                    if (existing != null) { connection.commit(); return existing; }
                    long sequence = nextSequence(connection, event.getRunId());
                    AgentRunEvent saved = event.withSequence(sequence);
                    String sql = "INSERT INTO " + table("events")
                        + " (event_id,run_id,sequence_no,occurred_at,payload) VALUES (?,?,?,?,?)";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, saved.getEventId()); statement.setString(2, saved.getRunId());
                        statement.setLong(3, saved.getSequence()); statement.setLong(4, saved.getOccurredAt());
                        statement.setBytes(5, serialize(saved)); statement.executeUpdate();
                    }
                    connection.commit(); return saved;
                } catch (SQLException error) { rollback(connection); }
            } catch (SQLException error) { if (attempt == 4) throw failure("append AgentRun event", error); }
            existing = loadById(event.getEventId());
            if (existing != null) return existing;
        }
        throw new IllegalStateException("Failed to append AgentRun event after concurrent retries");
    }

    @Override
    public List<AgentRunEvent> load(String runId, long afterSequence, int limit) {
        if (runId == null || afterSequence < 0 || limit <= 0) throw new IllegalArgumentException("invalid event query");
        List<AgentRunEvent> result = new ArrayList<>();
        String sql = "SELECT payload FROM " + table("events")
            + " WHERE run_id=? AND sequence_no>? ORDER BY sequence_no";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId); statement.setLong(2, afterSequence); statement.setMaxRows(limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(deserialize(rows.getBytes(1), AgentRunEvent.class));
            }
            return result;
        } catch (SQLException error) { throw failure("load AgentRun events", error); }
    }

    private long nextSequence(Connection connection, String runId) throws SQLException {
        String select = "SELECT next_sequence FROM " + table("event_sequences") + " WHERE run_id=? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setString(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    long sequence = row.getLong(1);
                    try (PreparedStatement update = connection.prepareStatement("UPDATE " + table("event_sequences")
                        + " SET next_sequence=? WHERE run_id=?")) {
                        update.setLong(1, sequence + 1); update.setString(2, runId); update.executeUpdate();
                    }
                    return sequence;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table("event_sequences")
            + " (run_id,next_sequence) VALUES (?,?)")) {
            insert.setString(1, runId); insert.setLong(2, 2); insert.executeUpdate(); return 1;
        }
    }

    private AgentRunEvent loadById(String eventId) {
        try (Connection connection = connection()) { return loadById(connection, eventId); }
        catch (SQLException error) { throw failure("load AgentRun event", error); }
    }

    private AgentRunEvent loadById(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT payload FROM " + table("events") + " WHERE event_id=?")) {
            statement.setString(1, eventId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? deserialize(row.getBytes(1), AgentRunEvent.class) : null;
            }
        }
    }
}

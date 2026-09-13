package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.AgentTurnStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 使用 JDBC 事务、条件更新和乐观锁保存 AgentTurn Snapshot。
 */
public final class JdbcAgentTurnStore extends JdbcAgentStoreSupport implements AgentTurnStore {
    JdbcAgentTurnStore(JdbcAgentStoreConfig config) {
        super(config);
    }

    @Override
    public long currentTimeMillis() {
        try (Connection connection = connection(); PreparedStatement statement =
            connection.prepareStatement("SELECT CURRENT_TIMESTAMP");
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) throw new IllegalStateException("Database did not return current time");
            return row.getTimestamp(1).getTime();
        } catch (SQLException error) {
            throw failure("read database time", error);
        }
    }

    @Override
    public AgentTurnSnapshot load(String turnId) {
        try (Connection connection = connection()) {
            return load(connection, turnId);
        } catch (SQLException error) {
            throw failure("load AgentTurn " + turnId, error);
        }
    }

    @Override
    public AgentTurnSnapshot findActiveTurn(String conversationId) {
        if (conversationId == null) return null;
        String sql = "SELECT payload FROM " + table("turns")
            + " WHERE status NOT IN (?,?,?,?,?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, AgentTurnStatus.COMPLETED.name());
            statement.setString(2, AgentTurnStatus.FAILED.name());
            statement.setString(3, AgentTurnStatus.CANCELLED.name());
            statement.setString(4, AgentTurnStatus.MAX_ITERATIONS_REACHED.name());
            statement.setString(5, AgentTurnStatus.MAX_STEPS_REACHED.name());
            statement.setString(6, AgentTurnStatus.BUDGET_EXCEEDED.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    AgentTurnSnapshot snapshot = deserialize(rows.getBytes(1), AgentTurnSnapshot.class);
                    Object value = snapshot.getState().getMetadata().get("agentsflex.conversationId");
                    if (conversationId.equals(value)) return load(connection,
                        snapshot.getState().getTurnId());
                }
            }
            return null;
        } catch (SQLException error) {
            throw failure("find active AgentTurn", error);
        }
    }

    @Override
    public AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion) {
        requireSnapshot(snapshot);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                AgentTurnSnapshot saved = save(connection, snapshot, expectedVersion);
                connection.commit();
                return saved;
            } catch (RuntimeException | SQLException error) {
                rollback(connection);
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                throw error;
            }
        } catch (SQLException error) {
            throw failure("save AgentTurn " + snapshot.getState().getTurnId(), error);
        }
    }

    @Override
    public boolean requestCancellation(String turnId) {
        AgentTurnSnapshot current = load(turnId);
        if (current == null) throw new IllegalStateException("AgentTurn snapshot not found: " + turnId);
        if (current.getState().getStatus().isTerminal()
            || current.getState().isCancellationRequested()) return false;
        String sql = "UPDATE " + table("turns") + " SET cancellation_requested=? WHERE turn_id=? "
            + "AND cancellation_requested=? AND status NOT IN (?,?,?,?,?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, true);
            statement.setString(2, turnId);
            statement.setBoolean(3, false);
            statement.setString(4, AgentTurnStatus.COMPLETED.name());
            statement.setString(5, AgentTurnStatus.FAILED.name());
            statement.setString(6, AgentTurnStatus.CANCELLED.name());
            statement.setString(7, AgentTurnStatus.MAX_ITERATIONS_REACHED.name());
            statement.setString(8, AgentTurnStatus.MAX_STEPS_REACHED.name());
            statement.setString(9, AgentTurnStatus.BUDGET_EXCEEDED.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw failure("request AgentTurn cancellation", error);
        }
    }

    private AgentTurnSnapshot save(Connection connection, AgentTurnSnapshot snapshot, long expectedVersion) throws SQLException {
        AgentTurnSnapshot saved = snapshot.withVersion(expectedVersion + 1);
        if (expectedVersion == -1) {
            String sql = "INSERT INTO " + table("turns") + " (turn_id,version,status,next_runnable_at,"
                + "cancellation_requested,payload) VALUES (?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, saved);
                statement.executeUpdate();
                return saved;
            } catch (SQLException error) {
                String turnId = snapshot.getState().getTurnId();
                AgentTurnSnapshot actual = load(connection, turnId);
                if (actual != null) {
                    throw conflict(turnId, expectedVersion, actual.getState().getVersion());
                }
                throw error;
            }
        }
        String sql = "UPDATE " + table("turns") + " SET version=?,status=?,next_runnable_at=?,"
            + "cancellation_requested=CASE WHEN cancellation_requested=? THEN ? ELSE ? END,payload=? "
            + "WHERE turn_id=? AND version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            AgentTurnState state = saved.getState();
            statement.setLong(1, state.getVersion());
            statement.setString(2, state.getStatus().name());
            statement.setLong(3, state.getNextRunnableAt());
            statement.setBoolean(4, true);
            statement.setBoolean(5, true);
            statement.setBoolean(6, state.isCancellationRequested());
            statement.setBytes(7, serialize(saved));
            statement.setString(8, state.getTurnId());
            statement.setLong(9, expectedVersion);
            if (statement.executeUpdate() != 1) {
                AgentTurnSnapshot actual = load(connection, state.getTurnId());
                throw conflict(state.getTurnId(), expectedVersion,
                    actual == null ? -1 : actual.getState().getVersion());
            }
        }
        return load(connection, saved.getState().getTurnId());
    }

    private void bind(PreparedStatement statement, AgentTurnSnapshot saved) throws SQLException {
        AgentTurnState state = saved.getState();
        statement.setString(1, state.getTurnId());
        statement.setLong(2, state.getVersion());
        statement.setString(3, state.getStatus().name());
        statement.setLong(4, state.getNextRunnableAt());
        statement.setBoolean(5, state.isCancellationRequested());
        statement.setBytes(6, serialize(saved));
    }

    private AgentTurnSnapshot load(Connection connection, String turnId) throws SQLException {
        String sql = "SELECT version,status,next_runnable_at,cancellation_requested,payload "
            + "FROM " + table("turns") + " WHERE turn_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, turnId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                AgentTurnSnapshot payload = deserialize(row.getBytes(5), AgentTurnSnapshot.class);
                AgentTurnState state = payload.getState().toBuilder()
                    .version(row.getLong(1)).status(AgentTurnStatus.valueOf(row.getString(2)))
                    .nextRunnableAt(row.getLong(3))
                    .cancellationRequested(row.getBoolean(4)).build();
                return payload.withState(state);
            }
        }
    }

    private AgentTurnVersionConflictException conflict(String turnId, long expected, long actual) {
        return new AgentTurnVersionConflictException(turnId, expected, actual);
    }

    private void requireSnapshot(AgentTurnSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
    }
}

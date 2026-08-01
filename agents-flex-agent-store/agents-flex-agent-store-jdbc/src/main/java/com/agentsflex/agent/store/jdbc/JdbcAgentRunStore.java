package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentRunSnapshot;
import com.agentsflex.agent.AgentRunStatus;
import com.agentsflex.agent.store.AgentRunStore;
import com.agentsflex.agent.store.AgentRunVersionConflictException;
import com.agentsflex.agent.store.ParentChildRunSnapshots;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 使用 JDBC 事务、条件更新和乐观锁保存 AgentRun Checkpoint。 */
public final class JdbcAgentRunStore extends JdbcAgentStoreSupport implements AgentRunStore {
    JdbcAgentRunStore(JdbcAgentStoreConfig config) { super(config); }

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
    public AgentRunSnapshot load(String runId) {
        try (Connection connection = connection()) { return load(connection, runId); }
        catch (SQLException error) { throw failure("load AgentRun " + runId, error); }
    }

    @Override
    public AgentRunSnapshot save(AgentRunSnapshot snapshot, long expectedVersion) {
        requireSnapshot(snapshot);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                AgentRunSnapshot saved = save(connection, snapshot, expectedVersion);
                connection.commit();
                return saved;
            } catch (RuntimeException | SQLException error) {
                rollback(connection);
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                throw error;
            }
        } catch (SQLException error) { throw failure("save AgentRun " + snapshot.getRunId(), error); }
    }

    @Override
    public boolean requestCancellation(String runId) {
        AgentRunSnapshot current = load(runId);
        if (current == null) throw new IllegalStateException("AgentRun checkpoint not found: " + runId);
        if (current.getStatus().isTerminal() || current.isCancellationRequested()) return false;
        String sql = "UPDATE " + table("runs") + " SET cancellation_requested=? WHERE run_id=? "
            + "AND cancellation_requested=? AND status NOT IN (?,?,?,?,?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, true); statement.setString(2, runId); statement.setBoolean(3, false);
            statement.setString(4, AgentRunStatus.COMPLETED.name());
            statement.setString(5, AgentRunStatus.FAILED.name());
            statement.setString(6, AgentRunStatus.CANCELLED.name());
            statement.setString(7, AgentRunStatus.MAX_ITERATIONS_REACHED.name());
            statement.setString(8, AgentRunStatus.MAX_STEPS_REACHED.name());
            statement.setString(9, AgentRunStatus.BUDGET_EXCEEDED.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException error) { throw failure("request AgentRun cancellation", error); }
    }

    @Override
    public boolean isCancellationRequested(String runId) {
        AgentRunSnapshot snapshot = load(runId);
        return snapshot != null && snapshot.isCancellationRequested();
    }

    @Override
    public ParentChildRunSnapshots saveParentAndChild(AgentRunSnapshot parent, long expectedParentVersion,
                                                       AgentRunSnapshot child) {
        requireSnapshot(parent); requireSnapshot(child);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                AgentRunSnapshot savedParent = save(connection, parent, expectedParentVersion);
                AgentRunSnapshot savedChild = save(connection, child, -1);
                connection.commit();
                return new ParentChildRunSnapshots(savedParent, savedChild);
            } catch (RuntimeException | SQLException error) {
                rollback(connection);
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                throw error;
            }
        } catch (SQLException error) { throw failure("save parent and child AgentRun", error); }
    }

    @Override
    public List<AgentRunSnapshot> claimRunnable(String workerId, long now, long leaseMillis, int limit) {
        if (workerId == null || leaseMillis <= 0 || limit <= 0) throw new IllegalArgumentException("invalid lease request");
        List<AgentRunSnapshot> claimed = new ArrayList<>();
        String query = "SELECT r.run_id,r.version,r.parent_run_id FROM " + table("runs") + " r WHERE "
            + "((r.status IN (?,?)) OR (r.status=? AND r.next_run_at<=?) OR r.cancellation_requested=?) "
            + "AND (r.lease_owner IS NULL OR r.lease_until<=?) AND NOT EXISTS (SELECT 1 FROM " + table("runs")
            + " p WHERE p.run_id=r.parent_run_id AND p.lease_owner IS NOT NULL AND p.lease_until>?) ORDER BY r.next_run_at";
        try (Connection connection = connection(); PreparedStatement select = connection.prepareStatement(query)) {
            select.setString(1, AgentRunStatus.READY.name()); select.setString(2, AgentRunStatus.RUNNING.name());
            select.setString(3, AgentRunStatus.RETRY_SCHEDULED.name()); select.setLong(4, now);
            select.setBoolean(5, true); select.setLong(6, now); select.setLong(7, now);
            select.setMaxRows(Math.max(limit * 4, limit));
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next() && claimed.size() < limit) {
                    String runId = rows.getString(1); long version = rows.getLong(2);
                    String parentRunId = rows.getString(3);
                    String leaseId = UUID.randomUUID().toString();
                    String update = "UPDATE " + table("runs") + " SET lease_owner=?,lease_id=?,lease_until=?,version=version+1 "
                        + "WHERE run_id=? AND version=? AND (lease_owner IS NULL OR lease_until<=?) "
                        + "AND (? IS NULL OR NOT EXISTS (SELECT 1 FROM " + table("runs")
                        + " p WHERE p.run_id=? AND p.lease_owner IS NOT NULL AND p.lease_until>?))";
                    try (PreparedStatement claim = connection.prepareStatement(update)) {
                        claim.setString(1, workerId); claim.setString(2, leaseId);
                        claim.setLong(3, now + leaseMillis); claim.setString(4, runId);
                        claim.setLong(5, version); claim.setLong(6, now);
                        claim.setString(7, parentRunId); claim.setString(8, parentRunId);
                        claim.setLong(9, now);
                        if (claim.executeUpdate() == 1) claimed.add(load(connection, runId));
                    }
                }
            }
            return claimed;
        } catch (SQLException error) { throw failure("claim runnable AgentRuns", error); }
    }

    @Override
    public AgentRunSnapshot renewLease(String runId, String workerId, String leaseId,
                                       long now, long leaseUntil) {
        if (leaseUntil <= now) throw new IllegalArgumentException("leaseUntil must be after now");
        String sql = "UPDATE " + table("runs") + " SET lease_until=? WHERE run_id=? AND lease_owner=? "
            + "AND lease_id=? AND lease_until>?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, leaseUntil); statement.setString(2, runId);
            statement.setString(3, workerId); statement.setString(4, leaseId);
            statement.setLong(5, now);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("AgentRun lease is not owned by worker: " + workerId);
            return load(connection, runId);
        } catch (SQLException error) { throw failure("renew AgentRun lease", error); }
    }

    @Override
    public void releaseLease(String runId, String workerId, String leaseId) {
        String sql = "UPDATE " + table("runs") + " SET lease_owner=NULL,lease_id=NULL,lease_until=0 "
            + "WHERE run_id=? AND lease_owner=? AND lease_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId); statement.setString(2, workerId);
            statement.setString(3, leaseId); statement.executeUpdate();
        } catch (SQLException error) { throw failure("release AgentRun lease", error); }
    }

    @Override
    public List<AgentRunSnapshot> findTerminalChildrenWithWaitingParent(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be greater than 0");
        List<AgentRunSnapshot> result = new ArrayList<>();
        String sql = "SELECT c.run_id FROM " + table("runs") + " c JOIN " + table("runs")
            + " p ON p.run_id=c.parent_run_id WHERE p.status=? AND c.status IN (?,?,?,?,?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, AgentRunStatus.WAITING_FOR_CHILD.name());
            statement.setString(2, AgentRunStatus.COMPLETED.name());
            statement.setString(3, AgentRunStatus.FAILED.name());
            statement.setString(4, AgentRunStatus.CANCELLED.name());
            statement.setString(5, AgentRunStatus.MAX_ITERATIONS_REACHED.name());
            statement.setString(6, AgentRunStatus.MAX_STEPS_REACHED.name());
            statement.setString(7, AgentRunStatus.BUDGET_EXCEEDED.name());
            statement.setMaxRows(limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(load(connection, rows.getString(1)));
            }
            return result;
        } catch (SQLException error) {
            throw failure("find completed child AgentRuns", error);
        }
    }

    private AgentRunSnapshot save(Connection connection, AgentRunSnapshot snapshot, long expectedVersion) throws SQLException {
        AgentRunSnapshot saved = snapshot.withVersion(expectedVersion + 1);
        if (expectedVersion == -1) {
            String sql = "INSERT INTO " + table("runs") + " (run_id,version,status,next_run_at,lease_owner,lease_id,lease_until,"
                + "parent_run_id,cancellation_requested,payload) VALUES (?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, saved); statement.executeUpdate(); return saved;
            } catch (SQLException error) {
                AgentRunSnapshot actual = load(connection, snapshot.getRunId());
                if (actual != null) throw conflict(snapshot.getRunId(), expectedVersion, actual.getVersion());
                throw error;
            }
        }
        String sql = "UPDATE " + table("runs") + " SET version=?,status=?,next_run_at=?,lease_owner=?,lease_id=?,lease_until=?,"
            + "parent_run_id=?,cancellation_requested=CASE WHEN cancellation_requested=? THEN ? ELSE ? END,payload=? "
            + "WHERE run_id=? AND version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, saved.getVersion()); statement.setString(2, saved.getStatus().name());
            statement.setLong(3, saved.getNextRunAt()); statement.setString(4, saved.getLeaseOwner());
            statement.setString(5, saved.getLeaseId()); statement.setLong(6, saved.getLeaseUntil());
            statement.setString(7, saved.getParentRunId()); statement.setBoolean(8, true);
            statement.setBoolean(9, true); statement.setBoolean(10, saved.isCancellationRequested());
            statement.setBytes(11, serialize(saved)); statement.setString(12, saved.getRunId());
            statement.setLong(13, expectedVersion);
            if (statement.executeUpdate() != 1) {
                AgentRunSnapshot actual = load(connection, saved.getRunId());
                throw conflict(saved.getRunId(), expectedVersion, actual == null ? -1 : actual.getVersion());
            }
        }
        return load(connection, saved.getRunId());
    }

    private void bind(PreparedStatement statement, AgentRunSnapshot saved) throws SQLException {
        statement.setString(1, saved.getRunId()); statement.setLong(2, saved.getVersion());
        statement.setString(3, saved.getStatus().name()); statement.setLong(4, saved.getNextRunAt());
        statement.setString(5, saved.getLeaseOwner()); statement.setString(6, saved.getLeaseId());
        statement.setLong(7, saved.getLeaseUntil()); statement.setString(8, saved.getParentRunId());
        statement.setBoolean(9, saved.isCancellationRequested()); statement.setBytes(10, serialize(saved));
    }

    private AgentRunSnapshot load(Connection connection, String runId) throws SQLException {
        String sql = "SELECT version,status,next_run_at,lease_owner,lease_id,lease_until,parent_run_id,cancellation_requested,payload "
            + "FROM " + table("runs") + " WHERE run_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                AgentRunSnapshot payload = deserialize(row.getBytes(9), AgentRunSnapshot.class);
                return payload.toBuilder().version(row.getLong(1)).status(AgentRunStatus.valueOf(row.getString(2)))
                    .nextRunAt(row.getLong(3)).leaseOwner(row.getString(4)).leaseId(row.getString(5))
                    .leaseUntil(row.getLong(6)).parentRunId(row.getString(7))
                    .cancellationRequested(row.getBoolean(8)).build();
            }
        }
    }

    private AgentRunVersionConflictException conflict(String runId, long expected, long actual) {
        return new AgentRunVersionConflictException(runId, expected, actual);
    }

    private void requireSnapshot(AgentRunSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
    }
}

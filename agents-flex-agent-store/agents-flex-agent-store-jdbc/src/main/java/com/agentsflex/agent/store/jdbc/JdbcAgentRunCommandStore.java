package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.command.AgentRunCommand;
import com.agentsflex.agent.command.AgentRunCommandStatus;
import com.agentsflex.agent.command.AgentRunCommandStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 基于 JDBC 条件更新实现幂等提交和跨进程命令 Lease。 */
public final class JdbcAgentRunCommandStore extends JdbcAgentStoreSupport implements AgentRunCommandStore {
    JdbcAgentRunCommandStore(JdbcAgentStoreConfig config) { super(config); }

    @Override
    public AgentRunCommand submit(AgentRunCommand command) {
        if (command == null) throw new IllegalArgumentException("command must not be null");
        String sql = "INSERT INTO " + table("commands") + " (command_id,run_id,status,created_at,lease_owner,"
            + "lease_until,attempts,error_message,payload) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, command); statement.executeUpdate(); return command;
        } catch (SQLException error) {
            AgentRunCommand existing = load(command.getCommandId());
            if (existing == null) throw failure("submit AgentRun command", error);
            if (!sameCommand(existing, command)) {
                throw new IllegalArgumentException("commandId is already used by a different command: " + command.getCommandId());
            }
            return existing;
        }
    }

    @Override
    public AgentRunCommand load(String commandId) {
        String sql = "SELECT status,lease_owner,lease_until,attempts,error_message,payload FROM "
            + table("commands") + " WHERE command_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, commandId);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? decode(row) : null; }
        } catch (SQLException error) { throw failure("load AgentRun command", error); }
    }

    @Override
    public List<AgentRunCommand> claim(String workerId, long now, long leaseMillis, int limit) {
        if (workerId == null || leaseMillis <= 0 || limit <= 0) throw new IllegalArgumentException("invalid command claim request");
        List<AgentRunCommand> result = new ArrayList<>();
        String query = "SELECT command_id,attempts FROM " + table("commands")
            + " WHERE status=? OR (status=? AND lease_until<=?) ORDER BY created_at";
        try (Connection connection = connection(); PreparedStatement select = connection.prepareStatement(query)) {
            select.setString(1, AgentRunCommandStatus.PENDING.name());
            select.setString(2, AgentRunCommandStatus.CLAIMED.name()); select.setLong(3, now);
            select.setMaxRows(Math.max(limit * 4, limit));
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next() && result.size() < limit) {
                    String id = rows.getString(1); int attempts = rows.getInt(2);
                    String update = "UPDATE " + table("commands") + " SET status=?,lease_owner=?,lease_until=?,"
                        + "attempts=attempts+1,error_message=NULL WHERE command_id=? AND attempts=? "
                        + "AND (status=? OR (status=? AND lease_until<=?))";
                    try (PreparedStatement claim = connection.prepareStatement(update)) {
                        claim.setString(1, AgentRunCommandStatus.CLAIMED.name()); claim.setString(2, workerId);
                        claim.setLong(3, now + leaseMillis); claim.setString(4, id); claim.setInt(5, attempts);
                        claim.setString(6, AgentRunCommandStatus.PENDING.name());
                        claim.setString(7, AgentRunCommandStatus.CLAIMED.name()); claim.setLong(8, now);
                        if (claim.executeUpdate() == 1) result.add(load(id));
                    }
                }
            }
            return result;
        } catch (SQLException error) { throw failure("claim AgentRun commands", error); }
    }

    @Override public void acknowledge(String commandId, String workerId) {
        updateClaim(commandId, workerId, AgentRunCommandStatus.COMPLETED, null);
    }
    @Override public void release(String commandId, String workerId, String errorMessage) {
        updateClaim(commandId, workerId, AgentRunCommandStatus.PENDING, errorMessage);
    }
    @Override public void fail(String commandId, String workerId, String errorMessage) {
        updateClaim(commandId, workerId, AgentRunCommandStatus.FAILED, errorMessage);
    }

    private void updateClaim(String commandId, String workerId, AgentRunCommandStatus status, String error) {
        String sql = "UPDATE " + table("commands") + " SET status=?,lease_owner=NULL,lease_until=0,error_message=? "
            + "WHERE command_id=? AND status=? AND lease_owner=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name()); statement.setString(2, error); statement.setString(3, commandId);
            statement.setString(4, AgentRunCommandStatus.CLAIMED.name()); statement.setString(5, workerId);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("command is not claimed by worker: " + commandId);
        } catch (SQLException failure) { throw failure("update AgentRun command", failure); }
    }

    private AgentRunCommand decode(ResultSet row) throws SQLException {
        AgentRunCommand payload = deserialize(row.getBytes(6), AgentRunCommand.class);
        return new AgentRunCommand(payload.getCommandId(), payload.getRunId(), payload.getCommand(), payload.getCreatedAt(),
            AgentRunCommandStatus.valueOf(row.getString(1)), row.getString(2), row.getLong(3), row.getInt(4), row.getString(5));
    }

    private void bind(PreparedStatement statement, AgentRunCommand command) throws SQLException {
        statement.setString(1, command.getCommandId()); statement.setString(2, command.getRunId());
        statement.setString(3, command.getStatus().name()); statement.setLong(4, command.getCreatedAt());
        statement.setString(5, command.getLeaseOwner()); statement.setLong(6, command.getLeaseUntil());
        statement.setInt(7, command.getAttempts()); statement.setString(8, command.getErrorMessage());
        statement.setBytes(9, serialize(command));
    }

    private boolean sameCommand(AgentRunCommand left, AgentRunCommand right) {
        return left.getRunId().equals(right.getRunId())
            && left.getCommand().getType() == right.getCommand().getType()
            && Objects.equals(left.getCommand().getCorrelationId(), right.getCommand().getCorrelationId())
            && Objects.equals(left.getCommand().getContent(), right.getCommand().getContent())
            && left.getCommand().getMetadata().equals(right.getCommand().getMetadata());
    }
}

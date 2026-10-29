package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.context.AgentContextUsageState;
import com.agentsflex.agent.context.AgentContextUsageStateStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 基于 JDBC 的服务端 Usage 状态 CAS 存储。
 *
 * <p>每个状态以 {@code conversation_id + usage_scope_key} 为联合主键，并同时保存独立 version
 * 列和完整序列化 payload。version 列用于数据库原子条件更新，payload 用于恢复完整不可变状态。</p>
 */
public final class JdbcAgentContextUsageStateStore extends JdbcAgentStoreSupport
    implements AgentContextUsageStateStore {

    /**
     * 使用 Agent Store 的统一数据源、表前缀和序列化器。
     */
    JdbcAgentContextUsageStateStore(JdbcAgentStoreConfig config) {
        super(config);
    }

    /**
     * 按会话与模型作用域读取状态，不存在时返回 {@code null}。
     */
    @Override
    public AgentContextUsageState load(String conversationId, String usageScopeKey) {
        requireKey(conversationId, usageScopeKey);
        String sql = "SELECT payload FROM " + table("context_usage_states")
            + " WHERE conversation_id=? AND usage_scope_key=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            statement.setString(2, usageScopeKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? deserialize(row.getBytes(1), AgentContextUsageState.class) : null;
            }
        } catch (SQLException error) {
            throw failure("load context usage state", error);
        }
    }

    /**
     * 使用数据库条件更新实现 CAS 保存。
     *
     * <p>先尝试更新已有行；首次保存时 expectedVersion 为 0，再尝试插入。并发首次插入中，
     * 唯一键冲突的一方通过重新读取确认已有状态后返回 {@code false}，不会覆盖胜出者。</p>
     */
    @Override
    public boolean save(AgentContextUsageState state, long expectedVersion) {
        if (state == null || expectedVersion < 0
            || state.getVersion() != expectedVersion + 1) {
            throw new IllegalArgumentException("invalid usage state save");
        }
        requireKey(state.getConversationId(), state.getUsageScopeKey());
        byte[] payload = serialize(state);
        // 更新语句同时比较联合主键和旧版本，保证检查与写入在数据库中原子完成。
        String update = "UPDATE " + table("context_usage_states")
            + " SET version=?,payload=? WHERE conversation_id=? AND usage_scope_key=? AND version=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setLong(1, state.getVersion());
            statement.setBytes(2, payload);
            statement.setString(3, state.getConversationId());
            statement.setString(4, state.getUsageScopeKey());
            statement.setLong(5, expectedVersion);
            if (statement.executeUpdate() == 1) return true;
        } catch (SQLException error) {
            throw failure("save context usage state", error);
        }
        // 只有首次版本允许插入；后续版本更新失败直接视为 CAS 冲突。
        if (expectedVersion == 0) {
            String insert = "INSERT INTO " + table("context_usage_states")
                + " (conversation_id,usage_scope_key,version,payload) VALUES (?,?,?,?)";
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, state.getConversationId());
                statement.setString(2, state.getUsageScopeKey());
                statement.setLong(3, state.getVersion());
                statement.setBytes(4, payload);
                statement.executeUpdate();
                return true;
            } catch (SQLException error) {
                // 并发插入可能触发唯一键异常；若行已经存在，则按 CAS 失败处理。
                if (load(state.getConversationId(), state.getUsageScopeKey()) != null) return false;
                throw failure("insert context usage state", error);
            }
        }
        return false;
    }

    /**
     * 校验联合主键，避免空白 ID 形成无法审计或相互覆盖的状态记录。
     */
    private static void requireKey(String conversationId, String usageScopeKey) {
        if (conversationId == null || conversationId.trim().isEmpty()
            || usageScopeKey == null || usageScopeKey.trim().isEmpty()) {
            throw new IllegalArgumentException("conversationId and usageScopeKey must not be blank");
        }
    }
}

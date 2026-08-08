package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentContextCompressionState;
import com.agentsflex.agent.AgentContextCompressionStateStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 使用 JDBC 表保存会话上下文压缩状态。
 *
 * <p>状态正文使用配置的 {@code AgentStoreSerializer} 编码；版本列用于条件更新，首次创建使用
 * {@code expectedVersion=0}。冲突返回 {@code false}，数据库异常则抛出带原始异常的运行时异常。</p>
 */
public final class JdbcAgentContextCompressionStateStore extends JdbcAgentStoreSupport
    implements AgentContextCompressionStateStore {

    JdbcAgentContextCompressionStateStore(JdbcAgentStoreConfig config) {
        super(config);
    }

    @Override
    public AgentContextCompressionState load(String conversationId) {
        requireConversationId(conversationId);
        String sql = "SELECT payload FROM " + table("compression_states") + " WHERE conversation_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                return deserialize(row.getBytes(1), AgentContextCompressionState.class);
            }
        } catch (SQLException error) {
            throw failure("load compression state", error);
        }
    }

    @Override
    public boolean save(String conversationId, AgentContextCompressionState state, long expectedVersion) {
        requireConversationId(conversationId);
        if (state == null) throw new IllegalArgumentException("state must not be null");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        byte[] payload = serialize(state);
        String update = "UPDATE " + table("compression_states")
            + " SET version=?,payload=? WHERE conversation_id=? AND version=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setLong(1, state.getVersion());
            statement.setBytes(2, payload);
            statement.setString(3, conversationId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() == 1) return true;
        } catch (SQLException error) {
            throw failure("save compression state", error);
        }

        // The first write has no row to update. The primary key makes concurrent inserts fail atomically.
        if (expectedVersion == 0) {
            String insert = "INSERT INTO " + table("compression_states")
                + " (conversation_id,version,payload) VALUES (?,?,?)";
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, conversationId);
                statement.setLong(2, state.getVersion());
                statement.setBytes(3, payload);
                statement.executeUpdate();
                return true;
            } catch (SQLException error) {
                if (load(conversationId) != null) return false;
                throw failure("insert compression state", error);
            }
        }
        return false;
    }

    private static void requireConversationId(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty())
            throw new IllegalArgumentException("conversationId must not be blank");
    }
}

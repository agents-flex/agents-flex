package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.context.AgentArtifactReference;
import com.agentsflex.agent.context.AgentArtifactStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** 将大型工具结果作为 UTF-8 二进制内容保存到 JDBC。 */
public final class JdbcAgentArtifactStore extends JdbcAgentStoreSupport implements AgentArtifactStore {
    JdbcAgentArtifactStore(JdbcAgentStoreConfig config) { super(config); }

    @Override
    public AgentArtifactReference save(String runId, String mediaType, String content, Map<String, ?> metadata) {
        String value = content == null ? "" : content;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        AgentArtifactReference reference = new AgentArtifactReference(UUID.randomUUID().toString(), runId,
            mediaType, bytes.length, sha256(bytes), metadata);
        String sql = "INSERT INTO " + table("artifacts")
            + " (artifact_id,run_id,media_type,size_bytes,checksum,content) VALUES (?,?,?,?,?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reference.getArtifactId()); statement.setString(2, runId);
            statement.setString(3, mediaType); statement.setLong(4, bytes.length);
            statement.setString(5, reference.getChecksum()); statement.setBytes(6, bytes);
            statement.executeUpdate(); return reference;
        } catch (SQLException error) { throw failure("save Agent artifact", error); }
    }

    @Override
    public String load(String artifactId) {
        String sql = "SELECT content FROM " + table("artifacts") + " WHERE artifact_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifactId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new String(row.getBytes(1), StandardCharsets.UTF_8) : null;
            }
        } catch (SQLException error) { throw failure("load Agent artifact", error); }
    }

    private String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}

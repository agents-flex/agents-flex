package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.context.AgentArtifactReference;
import com.agentsflex.agent.context.AgentArtifactStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;

/** 将大型工具结果作为 UTF-8 字节保存到 Redis。 */
public final class RedisAgentArtifactStore extends RedisAgentStoreSupport implements AgentArtifactStore {
    RedisAgentArtifactStore(RedisAgentStoreConfig config) { super(config); }

    @Override
    public AgentArtifactReference save(String runId, String mediaType, String content, Map<String, ?> metadata) {
        String value = content == null ? "" : content; byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString(); jedis.set(key("artifact", id).getBytes(StandardCharsets.UTF_8), bytes);
        return new AgentArtifactReference(id, runId, mediaType, bytes.length, sha256(bytes), metadata);
    }

    @Override
    public String load(String artifactId) {
        byte[] bytes = jedis.get(key("artifact", artifactId).getBytes(StandardCharsets.UTF_8));
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
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

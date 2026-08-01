package com.agentsflex.core.agent.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 进程内 Artifact Store，适合测试和单进程应用。 */
public final class InMemoryAgentArtifactStore implements AgentArtifactStore {
    private final Map<String, String> contents = new LinkedHashMap<>();

    @Override
    public synchronized AgentArtifactReference save(String runId, String mediaType,
                                                    String content, Map<String, ?> metadata) {
        String value = content == null ? "" : content;
        String id = UUID.randomUUID().toString();
        contents.put(id, value);
        return new AgentArtifactReference(id, runId, mediaType,
            value.getBytes(StandardCharsets.UTF_8).length, sha256(value), metadata);
    }

    @Override
    public synchronized String load(String artifactId) { return contents.get(artifactId); }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}

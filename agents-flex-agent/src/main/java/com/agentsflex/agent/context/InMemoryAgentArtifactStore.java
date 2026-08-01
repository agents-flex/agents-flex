package com.agentsflex.agent.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 进程内 Artifact Store，适合测试和单进程应用。
 *
 * <p>内容仅存在于当前 JVM 内存，不支持多实例共享和进程重启恢复。生产环境应替换为对象存储、
 * 数据库或其他持久化实现。</p>
 */
public final class InMemoryAgentArtifactStore implements AgentArtifactStore {
    /** artifactId 到 UTF-8 文本正文的进程内映射。 */
    private final Map<String, String> contents = new LinkedHashMap<>();

    /** 保存文本并计算 UTF-8 字节数与 SHA-256 校验值。 */
    @Override
    public synchronized AgentArtifactReference save(String runId, String mediaType,
                                                    String content, Map<String, ?> metadata) {
        String value = content == null ? "" : content;
        String id = UUID.randomUUID().toString();
        contents.put(id, value);
        return new AgentArtifactReference(id, runId, mediaType,
            value.getBytes(StandardCharsets.UTF_8).length, sha256(value), metadata);
    }

    /** 按稳定 ID 读取正文；不存在时返回 {@code null}。 */
    @Override
    public synchronized String load(String artifactId) { return contents.get(artifactId); }

    /** 计算 UTF-8 文本的小写十六进制 SHA-256。 */
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

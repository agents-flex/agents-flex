package com.agentsflex.agent.context;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外置内容在 {@link AgentArtifactStore} 中的稳定引用。
 *
 * <p>大型工具结果写入 Artifact Store 后，Prompt 中只保留该引用的序列化表示。调用方可使用
 * artifactId 读取完整正文，并通过 checksum 校验内容完整性。</p>
 */
public final class AgentArtifactReference implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Artifact Store 分配的内容 ID。 */
    private final String artifactId;
    /** 产生该内容的 AgentRun ID。 */
    private final String runId;
    /** 内容的 MIME 类型。 */
    private final String mediaType;
    /** 原始内容按字节计算的大小。 */
    private final long size;
    /** 存储实现计算的内容校验值。 */
    private final String checksum;
    /** 描述工具调用、编码等扩展信息的只读元数据。 */
    private final Map<String, Object> metadata;

    public AgentArtifactReference(String artifactId, String runId, String mediaType,
                                  long size, String checksum, Map<String, ?> metadata) {
        this.artifactId = artifactId;
        this.runId = runId;
        this.mediaType = mediaType;
        this.size = size;
        this.checksum = checksum;
        this.metadata = metadata == null ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    /** @return Artifact Store 中的稳定内容 ID */
    public String getArtifactId() { return artifactId; }
    /** @return 产生内容的 AgentRun ID */
    public String getRunId() { return runId; }
    /** @return 内容 MIME 类型 */
    public String getMediaType() { return mediaType; }
    /** @return 原始内容字节数 */
    public long getSize() { return size; }
    /** @return 内容校验值 */
    public String getChecksum() { return checksum; }
    /** @return 不可修改的扩展元数据 */
    public Map<String, Object> getMetadata() { return metadata; }
}

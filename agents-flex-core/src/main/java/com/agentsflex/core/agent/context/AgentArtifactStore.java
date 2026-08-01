package com.agentsflex.core.agent.context;

import java.util.Map;

/** 保存无法直接放入模型上下文的大型内容。 */
public interface AgentArtifactStore {
    /** 保存完整内容，并返回可写入 ToolMessage 和 Snapshot 的轻量引用。 */
    AgentArtifactReference save(String runId, String mediaType, String content,
                                Map<String, ?> metadata);
    /** 按 artifactId 读取完整内容，不存在时返回 null。 */
    String load(String artifactId);
}

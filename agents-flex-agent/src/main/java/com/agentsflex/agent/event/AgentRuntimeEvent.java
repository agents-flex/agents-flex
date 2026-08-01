package com.agentsflex.agent.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一条不可变的进程内实时 Agent 事件。
 *
 * <p>实时事件用于流式界面、进度通知和低延迟追踪，不承担跨进程可靠投递。事件按照
 * {@code runId} 分配递增序号；需要断点续读或审计时应使用 {@link AgentRunEvent} 和对应 Store。</p>
 */
public final class AgentRuntimeEvent {
    /** 单次发布生成的事件 ID，仅用于进程内关联。 */
    private final String eventId = UUID.randomUUID().toString();
    /** 产生事件的直接运行 ID。 */
    private final String runId;
    /** 父子运行树最顶层的运行 ID。 */
    private final String rootRunId;
    /** 父运行 ID；根运行没有父级时为 {@code null}。 */
    private final String parentRunId;
    /** 产生事件的 Agent 稳定 ID。 */
    private final String agentId;
    /** 产生事件的 Agent 配置版本。 */
    private final String agentVersion;
    /** 同一 runId 内从 1 开始递增的进程内序号。 */
    private final long sequence;
    /** 描述事件所处执行节点的类型。 */
    private final AgentRuntimeEventType type;
    /** 事件对象创建时的本机毫秒时间戳。 */
    private final long occurredAt = System.currentTimeMillis();
    /** 事件类型相关的只读结构化数据。 */
    private final Map<String, Object> data;

    /** 创建由 {@link AgentRuntimeEventStream} 发布的实时事件。 */
    public AgentRuntimeEvent(String runId, String rootRunId, String parentRunId,
                             String agentId, String agentVersion, long sequence,
                             AgentRuntimeEventType type, Map<String, ?> data) {
        this.runId = runId;
        this.rootRunId = rootRunId;
        this.parentRunId = parentRunId;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.sequence = sequence;
        this.type = type;
        this.data = data == null ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(data));
    }

    /** @return 进程内事件唯一 ID */
    public String getEventId() { return eventId; }
    /** @return 直接产生事件的运行 ID */
    public String getRunId() { return runId; }
    /** @return 父子运行树的根运行 ID */
    public String getRootRunId() { return rootRunId; }
    /** @return 父运行 ID；根运行返回 {@code null} */
    public String getParentRunId() { return parentRunId; }
    /** @return 产生事件的 Agent ID */
    public String getAgentId() { return agentId; }
    /** @return 产生事件的 Agent 配置版本 */
    public String getAgentVersion() { return agentVersion; }
    /** @return 同一 runId 内单调递增的进程内序号 */
    public long getSequence() { return sequence; }
    /** @return 实时事件类型 */
    public AgentRuntimeEventType getType() { return type; }
    /** @return 事件创建时的本机毫秒时间戳 */
    public long getOccurredAt() { return occurredAt; }
    /** @return 不可修改的结构化事件数据 */
    public Map<String, Object> getData() { return data; }
}

package com.agentsflex.core.agent.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 一条不可变的进程内实时 Agent 事件。 */
public final class AgentRuntimeEvent {
    private final String eventId = UUID.randomUUID().toString();
    private final String runId;
    private final String rootRunId;
    private final String parentRunId;
    private final String agentId;
    private final String agentVersion;
    private final long sequence;
    private final AgentRuntimeEventType type;
    private final long occurredAt = System.currentTimeMillis();
    private final Map<String, Object> data;

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

    public String getEventId() { return eventId; }
    public String getRunId() { return runId; }
    public String getRootRunId() { return rootRunId; }
    public String getParentRunId() { return parentRunId; }
    public String getAgentId() { return agentId; }
    public String getAgentVersion() { return agentVersion; }
    public long getSequence() { return sequence; }
    public AgentRuntimeEventType getType() { return type; }
    public long getOccurredAt() { return occurredAt; }
    public Map<String, Object> getData() { return data; }
}

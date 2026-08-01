/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.event;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AgentRun 的不可变持久化事件。
 *
 * <p>sequence 在同一个 runId 内单调递增，消费者使用它进行断点续读。eventId 用于存储层实现
 * 幂等写入；attributes 只保存可序列化的字符串信息，不引用模型、工具或异常对象。</p>
 */
public final class AgentRunEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String eventId;
    private final String runId;
    private final long sequence;
    private final AgentRunEventType type;
    private final long occurredAt;
    private final Map<String, String> attributes;

    private AgentRunEvent(String eventId, String runId, long sequence,
                          AgentRunEventType type, long occurredAt,
                          Map<String, String> attributes) {
        if (eventId == null || runId == null || type == null) {
            throw new IllegalArgumentException("eventId, runId and type must not be null");
        }
        this.eventId = eventId;
        this.runId = runId;
        this.sequence = sequence;
        this.type = type;
        this.occurredAt = occurredAt;
        this.attributes = attributes == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    /** 创建一个等待 EventStore 分配序号的新事件。 */
    public static AgentRunEvent create(String runId, AgentRunEventType type,
                                       Map<String, String> attributes) {
        return new AgentRunEvent(UUID.randomUUID().toString(), runId, 0,
            type, System.currentTimeMillis(), attributes);
    }

    /** 返回内容相同但带有持久化序号的事件。 */
    public AgentRunEvent withSequence(long sequence) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be greater than 0");
        }
        return new AgentRunEvent(eventId, runId, sequence, type, occurredAt, attributes);
    }

    /** 返回与当前事件完全隔离的副本。 */
    public AgentRunEvent copy() {
        return new AgentRunEvent(eventId, runId, sequence, type, occurredAt, attributes);
    }

    public String getEventId() { return eventId; }
    public String getRunId() { return runId; }
    public long getSequence() { return sequence; }
    public AgentRunEventType getType() { return type; }
    public long getOccurredAt() { return occurredAt; }
    public Map<String, String> getAttributes() { return attributes; }
}

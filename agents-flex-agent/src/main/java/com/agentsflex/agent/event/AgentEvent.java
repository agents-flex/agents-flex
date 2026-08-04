/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.event;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 执行过程中发布的不可变事件。
 *
 * <p>事件只在当前 Runner 进程内同步投递。sequence 在同一 runId 内递增，但不是持久化游标；
 * 需要可靠存储时，应由业务监听器将事件写入自己的数据库、消息队列或 Outbox。</p>
 */
public final class AgentEvent {

    /**
     * 每次发布时生成的事件唯一 ID，仅用于关联和业务侧去重。
     */
    private final String eventId;
    /**
     * 直接产生该事件的 AgentRun ID。
     */
    private final String runId;
    /**
     * 当前父子 Run 树的根 Run ID；根 Run 通常与 runId 相同。
     */
    private final String rootRunId;
    /**
     * 直接父 Run ID；根 Run 没有父级时为 {@code null}。
     */
    private final String parentRunId;
    /**
     * 产生事件的 Agent 稳定标识。
     */
    private final String agentId;
    /**
     * 产生事件时使用的 Agent 定义版本。
     */
    private final String agentVersion;
    /**
     * 当前 Runner 内同一活动 runId 从 1 开始递增的事件序号。
     */
    private final long sequence;
    /**
     * 描述执行节点的事件类型。
     */
    private final AgentEventType type;
    /**
     * 事件对象创建时的本机毫秒时间戳。
     */
    private final long occurredAt;
    /**
     * 事件公共状态和类型专属数据组成的深度只读 Map。
     */
    private final Map<String, Object> data;

    /**
     * 创建一条不可变事件。
     *
     * <p>data 会在构造时深度复制：Map、Iterable 和数组转换为不可修改集合，基础不可变值原样保留，
     * 其他对象转换为字符串。因此监听器不会通过事件数据意外修改 Runner 内部状态。</p>
     *
     * @param runId        直接产生事件的 Run ID
     * @param rootRunId    父子 Run 树的根 Run ID
     * @param parentRunId  直接父 Run ID，根 Run 可为 {@code null}
     * @param agentId      Agent 稳定标识
     * @param agentVersion Agent 定义版本
     * @param sequence     当前 Runner 内的正数事件序号
     * @param type         事件类型
     * @param data         事件数据，可为 {@code null}
     */
    public AgentEvent(String runId, String rootRunId, String parentRunId,
                      String agentId, String agentVersion, long sequence,
                      AgentEventType type, Map<String, ?> data) {
        if (runId == null || agentId == null || agentVersion == null || type == null) {
            throw new IllegalArgumentException(
                "runId, agentId, agentVersion and type must not be null");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be greater than 0");
        }
        this.eventId = UUID.randomUUID().toString();
        this.runId = runId;
        this.rootRunId = rootRunId;
        this.parentRunId = parentRunId;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.sequence = sequence;
        this.type = type;
        this.occurredAt = System.currentTimeMillis();
        this.data = immutableMap(data);
    }

    /**
     * @return 当前事件的唯一 ID
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * @return 直接产生事件的 Run ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * @return 父子 Run 树的根 Run ID
     */
    public String getRootRunId() {
        return rootRunId;
    }

    /**
     * @return 直接父 Run ID；根 Run 返回 {@code null}
     */
    public String getParentRunId() {
        return parentRunId;
    }

    /**
     * @return 产生事件的 Agent ID
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * @return 产生事件时使用的 Agent 版本
     */
    public String getAgentVersion() {
        return agentVersion;
    }

    /**
     * @return 当前 Runner 内同一活动 Run 的递增序号
     */
    public long getSequence() {
        return sequence;
    }

    /**
     * @return 执行节点对应的事件类型
     */
    public AgentEventType getType() {
        return type;
    }

    /**
     * @return 事件创建时的本机毫秒时间戳
     */
    public long getOccurredAt() {
        return occurredAt;
    }

    /**
     * @return 不可修改的结构化事件数据
     */
    public Map<String, Object> getData() {
        return data;
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("event data key must not be null");
            }
            copy.put(entry.getKey(), immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
            || value instanceof Character || value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long || value instanceof Float
            || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
            || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException("nested event data key must not be null");
                }
                copy.put(String.valueOf(entry.getKey()), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?>) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Iterable<?>) value) copy.add(immutableValue(item));
            return Collections.unmodifiableList(copy);
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                copy.add(immutableValue(Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        return String.valueOf(value);
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内追加式事件存储，适合默认运行模式、单元测试和本地开发。
 *
 * <p>同一 runId 的 sequence 按追加顺序从 1 递增，eventId 重复追加时返回原事件。数据不会跨
 * JVM 共享或在重启后保留，生产环境应替换为持久化 EventStore。</p>
 */
public final class InMemoryAgentRunEventStore implements AgentRunEventStore {

    /** 每个 Run 按序保存的事件列表。 */
    private final Map<String, List<AgentRunEvent>> eventsByRun = new HashMap<>();
    /** 用于实现 eventId 幂等追加的全局索引。 */
    private final Map<String, AgentRunEvent> eventsById = new LinkedHashMap<>();

    /** 幂等追加事件并分配同一 Run 内的下一个 sequence。 */
    @Override
    public synchronized AgentRunEvent append(AgentRunEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        AgentRunEvent existing = eventsById.get(event.getEventId());
        if (existing != null) {
            return existing.copy();
        }
        List<AgentRunEvent> runEvents = eventsByRun.computeIfAbsent(
            event.getRunId(), key -> new ArrayList<>());
        AgentRunEvent saved = event.withSequence(runEvents.size() + 1L);
        runEvents.add(saved.copy());
        eventsById.put(saved.getEventId(), saved.copy());
        return saved.copy();
    }

    /** 按 sequence 独占游标读取指定数量的事件副本。 */
    @Override
    public synchronized List<AgentRunEvent> load(String runId, long afterSequence, int limit) {
        if (runId == null || afterSequence < 0 || limit <= 0) {
            throw new IllegalArgumentException("invalid event query");
        }
        List<AgentRunEvent> runEvents = eventsByRun.get(runId);
        if (runEvents == null || runEvents.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentRunEvent> result = new ArrayList<>();
        for (AgentRunEvent event : runEvents) {
            if (event.getSequence() > afterSequence) {
                result.add(event.copy());
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
}

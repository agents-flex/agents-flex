/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 进程内追加式事件存储，适合默认运行模式、单元测试和本地开发。 */
public final class InMemoryAgentRunEventStore implements AgentRunEventStore {

    private final Map<String, List<AgentRunEvent>> eventsByRun = new HashMap<>();
    private final Map<String, AgentRunEvent> eventsById = new LinkedHashMap<>();

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

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import java.util.HashMap;
import java.util.Map;

/** 进程内任务计划存储，适合默认运行模式、单元测试和本地开发。 */
public final class InMemoryAgentTaskStore implements AgentTaskStore {

    private final Map<String, AgentTaskPlanSnapshot> plans = new HashMap<>();
    private final Map<String, String> planIdsByRootRun = new HashMap<>();

    @Override
    public synchronized AgentTaskPlanSnapshot load(String planId) {
        AgentTaskPlanSnapshot snapshot = plans.get(planId);
        return snapshot == null ? null : snapshot.copy();
    }

    @Override
    public synchronized AgentTaskPlanSnapshot loadByRootRunId(String rootRunId) {
        String planId = planIdsByRootRun.get(rootRunId);
        return planId == null ? null : load(planId);
    }

    @Override
    public synchronized AgentTaskPlanSnapshot save(AgentTaskPlanSnapshot snapshot,
                                                   long expectedVersion) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        AgentTaskPlanSnapshot current = plans.get(snapshot.getPlanId());
        long actualVersion = current == null ? -1 : current.getVersion();
        if (actualVersion != expectedVersion) {
            throw new AgentTaskPlanVersionConflictException(snapshot.getPlanId(),
                expectedVersion, actualVersion);
        }
        String mappedPlanId = planIdsByRootRun.get(snapshot.getRootRunId());
        if (mappedPlanId != null && !mappedPlanId.equals(snapshot.getPlanId())) {
            throw new IllegalStateException(
                "root AgentRun already has a task plan: " + snapshot.getRootRunId());
        }
        AgentTaskPlanSnapshot saved = snapshot.toBuilder()
            .updatedAt(System.currentTimeMillis())
            .version(expectedVersion + 1)
            .build();
        plans.put(saved.getPlanId(), saved.copy());
        planIdsByRootRun.put(saved.getRootRunId(), saved.getPlanId());
        return saved.copy();
    }
}

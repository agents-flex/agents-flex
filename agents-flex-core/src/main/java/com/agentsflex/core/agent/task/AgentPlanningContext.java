/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 规划任务时可使用的目标、根运行标识和业务上下文。 */
public final class AgentPlanningContext {

    private final String goal;
    private final String rootRunId;
    private final Map<String, Object> metadata;

    public AgentPlanningContext(String goal, String rootRunId, Map<String, Object> metadata) {
        this.goal = goal;
        this.rootRunId = rootRunId;
        this.metadata = metadata == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    public String getGoal() { return goal; }
    public String getRootRunId() { return rootRunId; }
    public Map<String, Object> getMetadata() { return metadata; }
}

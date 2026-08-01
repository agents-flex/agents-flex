/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 规划器生成的任务拆分结果。 */
public final class AgentTaskPlan {

    private final String goal;
    private final List<AgentTask> tasks;

    public AgentTaskPlan(String goal, List<AgentTask> tasks) {
        if (!StringUtil.hasText(goal)) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty");
        }
        this.goal = goal;
        List<AgentTask> copies = new ArrayList<>(tasks.size());
        for (AgentTask task : tasks) {
            if (task == null) {
                throw new IllegalArgumentException("task must not be null");
            }
            copies.add(task.copy());
        }
        this.tasks = Collections.unmodifiableList(copies);
    }

    public String getGoal() { return goal; }

    public List<AgentTask> getTasks() {
        List<AgentTask> copies = new ArrayList<>(tasks.size());
        for (AgentTask task : tasks) { copies.add(task.copy()); }
        return Collections.unmodifiableList(copies);
    }
}

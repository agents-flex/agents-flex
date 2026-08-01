/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 可持久化的任务计划、任务进度和当前活动 Run 快照。 */
public final class AgentTaskPlanSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String planId;
    private final String rootRunId;
    private final String goal;
    private final AgentTaskPlanStatus status;
    private final List<AgentTask> tasks;
    private final String activeTaskId;
    private final String activeRunId;
    private final String error;
    private final long createdAt;
    private final long updatedAt;
    private final long completedAt;
    private final long version;

    private AgentTaskPlanSnapshot(Builder builder) {
        this.planId = builder.planId;
        this.rootRunId = builder.rootRunId;
        this.goal = builder.goal;
        this.status = builder.status;
        this.tasks = copyTasks(builder.tasks);
        this.activeTaskId = builder.activeTaskId;
        this.activeRunId = builder.activeRunId;
        this.error = builder.error;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.completedAt = builder.completedAt;
        this.version = builder.version;
    }

    public static Builder builder(String planId, String rootRunId, String goal) {
        return new Builder(planId, rootRunId, goal);
    }

    public Builder toBuilder() {
        return new Builder(planId, rootRunId, goal)
            .status(status)
            .tasks(tasks)
            .activeTaskId(activeTaskId)
            .activeRunId(activeRunId)
            .error(error)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .completedAt(completedAt)
            .version(version);
    }

    public AgentTaskPlanSnapshot copy() { return toBuilder().build(); }

    public AgentTaskPlanSnapshot withVersion(long value) {
        return toBuilder().version(value).build();
    }

    public String getPlanId() { return planId; }
    public String getRootRunId() { return rootRunId; }
    public String getGoal() { return goal; }
    public AgentTaskPlanStatus getStatus() { return status; }
    public List<AgentTask> getTasks() { return copyTasks(tasks); }
    public String getActiveTaskId() { return activeTaskId; }
    public String getActiveRunId() { return activeRunId; }
    public String getError() { return error; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public long getCompletedAt() { return completedAt; }
    public long getVersion() { return version; }

    public AgentTask getCurrentTask() {
        if (activeTaskId == null) { return null; }
        for (AgentTask task : tasks) {
            if (activeTaskId.equals(task.getId())) { return task.copy(); }
        }
        return null;
    }

    private static List<AgentTask> copyTasks(List<AgentTask> source) {
        if (source == null || source.isEmpty()) { return Collections.emptyList(); }
        List<AgentTask> result = new ArrayList<>(source.size());
        for (AgentTask task : source) { result.add(task.copy()); }
        return Collections.unmodifiableList(result);
    }

    /** 构建任务计划的持久化状态。 */
    public static final class Builder {
        private final String planId;
        private final String rootRunId;
        private final String goal;
        private AgentTaskPlanStatus status = AgentTaskPlanStatus.READY;
        private List<AgentTask> tasks = Collections.emptyList();
        private String activeTaskId;
        private String activeRunId;
        private String error;
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = System.currentTimeMillis();
        private long completedAt;
        private long version = -1;

        private Builder(String planId, String rootRunId, String goal) {
            this.planId = planId;
            this.rootRunId = rootRunId;
            this.goal = goal;
        }

        public Builder status(AgentTaskPlanStatus value) { this.status = value; return this; }
        public Builder tasks(List<AgentTask> value) { this.tasks = value; return this; }
        public Builder activeTaskId(String value) { this.activeTaskId = value; return this; }
        public Builder activeRunId(String value) { this.activeRunId = value; return this; }
        public Builder error(String value) { this.error = value; return this; }
        public Builder createdAt(long value) { this.createdAt = value; return this; }
        public Builder updatedAt(long value) { this.updatedAt = value; return this; }
        public Builder completedAt(long value) { this.completedAt = value; return this; }
        public Builder version(long value) { this.version = value; return this; }

        public AgentTaskPlanSnapshot build() {
            if (!StringUtil.hasText(planId) || !StringUtil.hasText(rootRunId)
                || !StringUtil.hasText(goal)) {
                throw new IllegalStateException("planId, rootRunId and goal must not be blank");
            }
            if (status == null || tasks == null || tasks.isEmpty()) {
                throw new IllegalStateException("plan status and tasks are required");
            }
            return new AgentTaskPlanSnapshot(this);
        }
    }
}

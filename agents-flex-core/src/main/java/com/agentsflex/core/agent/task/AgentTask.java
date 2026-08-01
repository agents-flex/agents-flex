/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;
import java.util.UUID;

/** 任务计划中的不可变任务及其执行结果。 */
public final class AgentTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String parentTaskId;
    private final String title;
    private final String description;
    private final int position;
    private final AgentTaskStatus status;
    private final String assignedAgentId;
    private final String childRunId;
    private final String result;
    private final String error;
    private final long createdAt;
    private final long startedAt;
    private final long completedAt;

    private AgentTask(Builder builder) {
        this.id = builder.id;
        this.parentTaskId = builder.parentTaskId;
        this.title = builder.title;
        this.description = builder.description;
        this.position = builder.position;
        this.status = builder.status;
        this.assignedAgentId = builder.assignedAgentId;
        this.childRunId = builder.childRunId;
        this.result = builder.result;
        this.error = builder.error;
        this.createdAt = builder.createdAt;
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
    }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    public Builder toBuilder() {
        return new Builder(title)
            .id(id)
            .parentTaskId(parentTaskId)
            .description(description)
            .position(position)
            .status(status)
            .assignedAgentId(assignedAgentId)
            .childRunId(childRunId)
            .result(result)
            .error(error)
            .createdAt(createdAt)
            .startedAt(startedAt)
            .completedAt(completedAt);
    }

    public AgentTask copy() { return toBuilder().build(); }

    public String getId() { return id; }
    public String getParentTaskId() { return parentTaskId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getPosition() { return position; }
    public AgentTaskStatus getStatus() { return status; }
    public String getAssignedAgentId() { return assignedAgentId; }
    public String getChildRunId() { return childRunId; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public long getCreatedAt() { return createdAt; }
    public long getStartedAt() { return startedAt; }
    public long getCompletedAt() { return completedAt; }

    /** 构建任务定义或任务状态的新版本。 */
    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private String parentTaskId;
        private final String title;
        private String description;
        private int position;
        private AgentTaskStatus status = AgentTaskStatus.PENDING;
        private String assignedAgentId;
        private String childRunId;
        private String result;
        private String error;
        private long createdAt = System.currentTimeMillis();
        private long startedAt;
        private long completedAt;

        private Builder(String title) {
            this.title = title;
        }

        public Builder id(String value) { this.id = value; return this; }
        public Builder parentTaskId(String value) { this.parentTaskId = value; return this; }
        public Builder description(String value) { this.description = value; return this; }
        public Builder position(int value) { this.position = value; return this; }
        public Builder status(AgentTaskStatus value) { this.status = value; return this; }
        public Builder assignedAgentId(String value) { this.assignedAgentId = value; return this; }
        public Builder childRunId(String value) { this.childRunId = value; return this; }
        public Builder result(String value) { this.result = value; return this; }
        public Builder error(String value) { this.error = value; return this; }
        public Builder createdAt(long value) { this.createdAt = value; return this; }
        public Builder startedAt(long value) { this.startedAt = value; return this; }
        public Builder completedAt(long value) { this.completedAt = value; return this; }

        public AgentTask build() {
            if (!StringUtil.hasText(id) || !StringUtil.hasText(title)) {
                throw new IllegalStateException("task id and title must not be blank");
            }
            if (position < 0 || status == null) {
                throw new IllegalStateException("task position and status are invalid");
            }
            return new AgentTask(this);
        }
    }
}

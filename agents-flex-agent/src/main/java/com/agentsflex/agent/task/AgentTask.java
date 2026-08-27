/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.task;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;
import java.util.UUID;

/**
 * 任务计划中的不可变任务及其执行结果。
 *
 * <p>任务定义和执行状态保存在父 Turn 的 Snapshot 中。任务开始后通过 childTurnId 关联实际执行
 * 的子 Turn；result 可以按规划策略截断，完整输出仍以子 Turn 的最终消息为准。</p>
 */
public final class AgentTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前计划内稳定且唯一的任务 ID。
     */
    private final String id;
    /**
     * 预留的上级任务 ID；没有任务层级时为 {@code null}。
     */
    private final String parentTaskId;
    /**
     * 用于任务列表展示的简短标题。
     */
    private final String title;
    /**
     * 发送给子 Agent 的具体执行要求。
     */
    private final String description;
    /**
     * 期望子 Agent 返回的内容或格式约束。
     */
    private final String expectedOutput;
    /**
     * 任务在当前计划中的零基执行顺序。
     */
    private final int position;
    /**
     * 当前任务生命周期状态。
     */
    private final AgentTaskStatus status;
    /**
     * 模型选择的目标 Agent ID；为空时使用父 Agent。
     */
    private final String assignedAgentId;
    /**
     * 执行该任务的子 Turn ID；尚未调度时为 {@code null}。
     */
    private final String childTurnId;
    /**
     * 写回父计划的子任务结果。
     */
    private final String result;
    /**
     * 子任务失败、取消或跳过时的原因。
     */
    private final String error;
    /**
     * 任务定义创建时间。
     */
    private final long createdAt;
    /**
     * 子 Turn 开始执行时间；尚未开始时为 0。
     */
    private final long startedAt;
    /**
     * 任务进入终止状态的时间；尚未结束时为 0。
     */
    private final long completedAt;

    /**
     * 从构建器冻结任务定义、状态、结果和生命周期时间。
     */
    private AgentTask(Builder builder) {
        this.id = builder.id;
        this.parentTaskId = builder.parentTaskId;
        this.title = builder.title;
        this.description = builder.description;
        this.expectedOutput = builder.expectedOutput;
        this.position = builder.position;
        this.status = builder.status;
        this.assignedAgentId = builder.assignedAgentId;
        this.childTurnId = builder.childTurnId;
        this.result = builder.result;
        this.error = builder.error;
        this.createdAt = builder.createdAt;
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
    }

    /**
     * 创建一个以标题为必填项的任务构建器。
     */
    public static Builder builder(String title) {
        return new Builder(title);
    }

    /**
     * 返回包含当前全部定义和状态的构建器，用于不可变状态转换。
     */
    public Builder toBuilder() {
        return new Builder(title)
            .id(id)
            .parentTaskId(parentTaskId)
            .description(description)
            .expectedOutput(expectedOutput)
            .position(position)
            .status(status)
            .assignedAgentId(assignedAgentId)
            .childTurnId(childTurnId)
            .result(result)
            .error(error)
            .createdAt(createdAt)
            .startedAt(startedAt)
            .completedAt(completedAt);
    }

    /**
     * @return 与当前任务完全隔离的副本
     */
    public AgentTask copy() {
        return toBuilder().build();
    }

    /**
     * @return 当前计划内的稳定任务 ID
     */
    public String getId() {
        return id;
    }

    /**
     * @return 上级任务 ID；没有层级时为 {@code null}
     */
    public String getParentTaskId() {
        return parentTaskId;
    }

    /**
     * @return 任务展示标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * @return 子 Agent 的执行要求
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return 期望输出说明；未配置时为 {@code null}
     */
    public String getExpectedOutput() {
        return expectedOutput;
    }

    /**
     * @return 任务在计划中的零基顺序
     */
    public int getPosition() {
        return position;
    }

    /**
     * @return 当前任务状态
     */
    public AgentTaskStatus getStatus() {
        return status;
    }

    /**
     * @return 目标 Agent ID；为空时由父 Agent 执行
     */
    public String getAssignedAgentId() {
        return assignedAgentId;
    }

    /**
     * @return 实际执行任务的子 Turn ID
     */
    public String getChildTurnId() {
        return childTurnId;
    }

    /**
     * @return 写回父计划的任务结果
     */
    public String getResult() {
        return result;
    }

    /**
     * @return 失败、取消或跳过原因
     */
    public String getError() {
        return error;
    }

    /**
     * @return 任务创建时间
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * @return 开始执行时间；未开始时为 0
     */
    public long getStartedAt() {
        return startedAt;
    }

    /**
     * @return 结束时间；未结束时为 0
     */
    public long getCompletedAt() {
        return completedAt;
    }

    /**
     * 构建任务定义或任务状态的新版本。
     */
    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private String parentTaskId;
        private final String title;
        private String description;
        private String expectedOutput;
        private int position;
        private AgentTaskStatus status = AgentTaskStatus.PENDING;
        private String assignedAgentId;
        private String childTurnId;
        private String result;
        private String error;
        private long createdAt = System.currentTimeMillis();
        private long startedAt;
        private long completedAt;

        /**
         * @param title 面向用户和事件展示的任务标题
         */
        private Builder(String title) {
            this.title = title;
        }

        /**
         * 设置计划内稳定任务 ID。
         */
        public Builder id(String value) {
            this.id = value;
            return this;
        }

        /**
         * 设置可选的上级任务 ID。
         */
        public Builder parentTaskId(String value) {
            this.parentTaskId = value;
            return this;
        }

        /**
         * 设置发送给子 Agent 的执行要求。
         */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        /**
         * 设置期望输出内容或格式。
         */
        public Builder expectedOutput(String value) {
            this.expectedOutput = value;
            return this;
        }

        /**
         * 设置任务在计划中的零基顺序。
         */
        public Builder position(int value) {
            this.position = value;
            return this;
        }

        /**
         * 设置任务生命周期状态。
         */
        public Builder status(AgentTaskStatus value) {
            this.status = value;
            return this;
        }

        /**
         * 设置模型选择的目标 Agent ID。
         */
        public Builder assignedAgentId(String value) {
            this.assignedAgentId = value;
            return this;
        }

        /**
         * 设置实际执行该任务的子 Turn ID。
         */
        public Builder childTurnId(String value) {
            this.childTurnId = value;
            return this;
        }

        /**
         * 设置写回父计划的任务结果。
         */
        public Builder result(String value) {
            this.result = value;
            return this;
        }

        /**
         * 设置任务终止原因。
         */
        public Builder error(String value) {
            this.error = value;
            return this;
        }

        /**
         * 设置任务定义创建时间。
         */
        public Builder createdAt(long value) {
            this.createdAt = value;
            return this;
        }

        /**
         * 设置任务开始执行时间。
         */
        public Builder startedAt(long value) {
            this.startedAt = value;
            return this;
        }

        /**
         * 设置任务进入终止状态的时间。
         */
        public Builder completedAt(long value) {
            this.completedAt = value;
            return this;
        }

        /**
         * 校验任务标识、标题、位置和状态后创建不可变任务。
         */
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

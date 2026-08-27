/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.task;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 当前 AgentTurn 内可持久化的任务计划和执行进度。
 *
 * <p>计划与 Turn 使用同一个 Snapshot 版本保存，避免独立计划存储与父子 Turn 状态不一致。</p>
 */
public final class AgentTaskPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前 Turn 内稳定的计划 ID。
     */
    private final String id;
    /**
     * 模型规划时声明的总体目标。
     */
    private final String goal;
    /**
     * 整个计划的生命周期状态。
     */
    private final AgentTaskPlanStatus status;
    /**
     * 按 position 排列的不可变任务列表。
     */
    private final List<AgentTask> tasks;
    /**
     * 当前正在执行的任务 ID；没有活动任务时为空。
     */
    private final String activeTaskId;
    /**
     * 最近一次任务失败或计划停止原因。
     */
    private final String error;
    /**
     * 模型已经成功调整计划的次数。
     */
    private final int revisionCount;
    /**
     * 最近一次调整计划的原因。
     */
    private final String lastRevisionReason;
    /**
     * 计划创建时间。
     */
    private final long createdAt;
    /**
     * 最近一次状态转换时间。
     */
    private final long updatedAt;
    /**
     * 父 Agent 完成最终汇总的时间。
     */
    private final long completedAt;

    /**
     * 从构建器生成不可变计划，并复制任务列表以隔离后续状态转换。
     */
    private AgentTaskPlan(Builder builder) {
        id = builder.id;
        goal = builder.goal;
        status = builder.status;
        tasks = copyTasks(builder.tasks);
        activeTaskId = builder.activeTaskId;
        error = builder.error;
        revisionCount = builder.revisionCount;
        lastRevisionReason = builder.lastRevisionReason;
        createdAt = builder.createdAt;
        updatedAt = builder.updatedAt;
        completedAt = builder.completedAt;
    }

    /**
     * 创建尚未执行的顺序任务计划。
     */
    public static AgentTaskPlan create(String goal, List<AgentTask> tasks) {
        return builder(goal).tasks(normalize(tasks)).build();
    }

    /**
     * 创建以总体目标为必填项的计划构建器。
     */
    public static Builder builder(String goal) {
        return new Builder(goal);
    }

    /**
     * 返回包含当前全部定义和状态的构建器。
     */
    public Builder toBuilder() {
        return new Builder(goal).id(id).status(status).tasks(tasks)
            .activeTaskId(activeTaskId).error(error).createdAt(createdAt)
            .revisionCount(revisionCount).lastRevisionReason(lastRevisionReason)
            .updatedAt(updatedAt).completedAt(completedAt);
    }

    /**
     * @return 与当前计划完全隔离的深拷贝
     */
    public AgentTaskPlan copy() {
        return toBuilder().build();
    }

    /**
     * @return 计划稳定 ID
     */
    public String getId() {
        return id;
    }

    /**
     * @return 总体目标
     */
    public String getGoal() {
        return goal;
    }

    /**
     * @return 计划生命周期状态
     */
    public AgentTaskPlanStatus getStatus() {
        return status;
    }

    /**
     * @return 按执行顺序排列的任务深拷贝
     */
    public List<AgentTask> getTasks() {
        return copyTasks(tasks);
    }

    /**
     * @return 当前活动任务 ID
     */
    public String getActiveTaskId() {
        return activeTaskId;
    }

    /**
     * @return 最近一次失败或停止原因
     */
    public String getError() {
        return error;
    }

    /**
     * @return 已成功重规划次数
     */
    public int getRevisionCount() {
        return revisionCount;
    }

    /**
     * @return 最近一次重规划原因
     */
    public String getLastRevisionReason() {
        return lastRevisionReason;
    }

    /**
     * @return 计划创建时间
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * @return 最近更新时间
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * @return 计划完成时间；未完成时为 0
     */
    public long getCompletedAt() {
        return completedAt;
    }

    /**
     * @return 当前活动任务副本；没有活动任务时为 {@code null}
     */
    public AgentTask getActiveTask() {
        return find(activeTaskId);
    }

    /**
     * 返回下一个尚未调度的任务。
     */
    public AgentTask getNextTask() {
        for (AgentTask task : tasks) {
            if (task.getStatus() == AgentTaskStatus.PENDING
                || task.getStatus() == AgentTaskStatus.READY) return task.copy();
        }
        return null;
    }

    /**
     * 记录任务已经创建子 Turn 并开始执行。
     */
    public AgentTaskPlan startTask(String taskId, String childTurnId, long now) {
        AgentTask current = require(taskId);
        AgentTask running = current.toBuilder().status(AgentTaskStatus.RUNNING)
            .childTurnId(childTurnId).startedAt(now).build();
        return toBuilder().status(AgentTaskPlanStatus.RUNNING)
            .tasks(replace(running)).activeTaskId(taskId).updatedAt(now).build();
    }

    /**
     * 记录当前子任务的最终结果。
     */
    public AgentTaskPlan finishActiveTask(AgentTaskStatus taskStatus, String result,
                                          String taskError, long now) {
        AgentTask current = require(activeTaskId);
        AgentTask finished = current.toBuilder().status(taskStatus).result(result)
            .error(taskError).completedAt(now).build();
        return toBuilder().status(AgentTaskPlanStatus.RUNNING).tasks(replace(finished))
            .activeTaskId(null).error(taskError).updatedAt(now).build();
    }

    /**
     * 停止计划并将尚未执行的任务标记为跳过。
     */
    public AgentTaskPlan stop(String reason, long now) {
        List<AgentTask> values = new ArrayList<>();
        for (AgentTask task : tasks) {
            if (task.getStatus() == AgentTaskStatus.PENDING
                || task.getStatus() == AgentTaskStatus.READY) {
                values.add(task.toBuilder().status(AgentTaskStatus.SKIPPED)
                    .error(reason).completedAt(now).build());
            } else values.add(task);
        }
        return toBuilder().status(AgentTaskPlanStatus.FINALIZING).tasks(values)
            .activeTaskId(null).error(reason).updatedAt(now).build();
    }

    /**
     * 标记所有任务已处理，父 Agent 可以生成最终汇总。
     */
    public AgentTaskPlan beginFinalizing(long now) {
        return toBuilder().status(AgentTaskPlanStatus.FINALIZING)
            .activeTaskId(null).updatedAt(now).build();
    }

    /**
     * 子任务失败后进入等待模型调整计划的状态。
     */
    public AgentTaskPlan beginReplanning(long now) {
        return toBuilder().status(AgentTaskPlanStatus.REPLANNING)
            .activeTaskId(null).updatedAt(now).build();
    }

    /**
     * 使用模型提交的新列表调整全部尚未执行的任务。
     *
     * <p>已经结束的任务始终保留。允许修改时，可以更新或移除原有待执行任务；允许追加时，可以
     * 引入新的任务 ID。该方法不会修改正在执行的任务。</p>
     */
    public AgentTaskPlan revisePending(List<AgentTask> requested, String reason,
                                       boolean revisionAllowed, boolean appendAllowed,
                                       int maxTasks, long now) {
        if (status != AgentTaskPlanStatus.REPLANNING || activeTaskId != null) {
            throw new IllegalStateException("task plan is not waiting for revision");
        }
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("revised tasks must not be empty");
        }
        Map<String, AgentTask> pendingById = new LinkedHashMap<>();
        Set<String> immutableIds = new HashSet<>();
        List<AgentTask> values = new ArrayList<>();
        for (AgentTask task : tasks) {
            if (task.getStatus() == AgentTaskStatus.PENDING
                || task.getStatus() == AgentTaskStatus.READY) {
                pendingById.put(task.getId(), task);
            } else {
                immutableIds.add(task.getId());
                values.add(task);
            }
        }
        Set<String> requestedIds = new HashSet<>();
        for (AgentTask task : requested) {
            if (task == null || !requestedIds.add(task.getId()) || immutableIds.contains(task.getId())) {
                throw new IllegalArgumentException(
                    "revised task IDs must be unique and must not reuse executed tasks");
            }
            AgentTask existing = pendingById.get(task.getId());
            if (existing == null && !appendAllowed) {
                throw new IllegalArgumentException("appending task is not allowed: " + task.getId());
            }
            if (existing != null && !revisionAllowed && !sameDefinition(existing, task)) {
                throw new IllegalArgumentException("revising task is not allowed: " + task.getId());
            }
            values.add(task.toBuilder().status(AgentTaskStatus.PENDING)
                .childTurnId(null).result(null).error(null).startedAt(0).completedAt(0).build());
        }
        if (!revisionAllowed && !requestedIds.containsAll(pendingById.keySet())) {
            throw new IllegalArgumentException("removing pending task is not allowed");
        }
        if (values.size() > maxTasks) {
            throw new IllegalArgumentException("task count exceeds maxTasks: " + maxTasks);
        }
        List<AgentTask> positioned = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            positioned.add(values.get(index).toBuilder().position(index).build());
        }
        return toBuilder().status(AgentTaskPlanStatus.RUNNING).tasks(positioned)
            .revisionCount(revisionCount + 1).lastRevisionReason(reason)
            .updatedAt(now).build();
    }

    /**
     * 标记父 Agent 已经完成最终汇总。
     */
    public AgentTaskPlan complete(long now) {
        return toBuilder().status(AgentTaskPlanStatus.COMPLETED)
            .completedAt(now).updatedAt(now).build();
    }

    /**
     * 按稳定 ID 查询任务并返回副本。
     *
     * @param taskId 任务 ID
     * @return 匹配任务；不存在时返回 {@code null}
     */
    private AgentTask find(String taskId) {
        if (taskId == null) return null;
        for (AgentTask task : tasks) if (taskId.equals(task.getId())) return task.copy();
        return null;
    }

    /**
     * 查询状态转换必需的任务。
     *
     * @throws IllegalStateException 指定任务不存在时抛出
     */
    private AgentTask require(String taskId) {
        AgentTask task = find(taskId);
        if (task == null) throw new IllegalStateException("task not found: " + taskId);
        return task;
    }

    /**
     * 创建替换指定任务后的新列表，原计划保持不变。
     *
     * @param replacement 携带新状态的任务
     * @return 替换后的任务列表
     */
    private List<AgentTask> replace(AgentTask replacement) {
        List<AgentTask> values = new ArrayList<>(tasks.size());
        for (AgentTask task : tasks) {
            values.add(task.getId().equals(replacement.getId()) ? replacement : task);
        }
        return values;
    }

    /**
     * 比较任务业务定义，不比较执行状态、子 Turn 和结果。
     */
    private static boolean sameDefinition(AgentTask left, AgentTask right) {
        return java.util.Objects.equals(left.getTitle(), right.getTitle())
            && java.util.Objects.equals(left.getDescription(), right.getDescription())
            && java.util.Objects.equals(left.getExpectedOutput(), right.getExpectedOutput())
            && java.util.Objects.equals(left.getAssignedAgentId(), right.getAssignedAgentId());
    }

    /**
     * 校验任务非空且 ID 唯一，重置运行字段并生成连续 position。
     *
     * @return 可安全用于新计划的任务列表
     */
    private static List<AgentTask> normalize(List<AgentTask> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty");
        }
        List<AgentTask> values = new ArrayList<>(source.size());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < source.size(); i++) {
            AgentTask task = source.get(i);
            if (task == null || !ids.add(task.getId())) {
                throw new IllegalArgumentException("task must be non-null and IDs must be unique");
            }
            values.add(task.toBuilder().position(i).status(AgentTaskStatus.PENDING)
                .childTurnId(null).result(null).error(null).startedAt(0).completedAt(0).build());
        }
        return values;
    }

    /**
     * 深复制任务列表并返回不可修改视图。
     */
    private static List<AgentTask> copyTasks(List<AgentTask> source) {
        List<AgentTask> values = new ArrayList<>();
        if (source != null) for (AgentTask task : source) values.add(task.copy());
        return Collections.unmodifiableList(values);
    }

    /**
     * 计划状态构建器，主要用于 Snapshot 反序列化和不可变状态转换。
     */
    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private final String goal;
        private AgentTaskPlanStatus status = AgentTaskPlanStatus.READY;
        private List<AgentTask> tasks = Collections.emptyList();
        private String activeTaskId;
        private String error;
        private int revisionCount;
        private String lastRevisionReason;
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = System.currentTimeMillis();
        private long completedAt;

        /**
         * @param goal 计划需要完成的总体目标
         */
        private Builder(String goal) {
            this.goal = goal;
        }

        /**
         * 设置计划稳定 ID。
         */
        public Builder id(String value) {
            id = value;
            return this;
        }

        /**
         * 设置计划生命周期状态。
         */
        public Builder status(AgentTaskPlanStatus value) {
            status = value;
            return this;
        }

        /**
         * 设置任务列表。
         */
        public Builder tasks(List<AgentTask> value) {
            tasks = value;
            return this;
        }

        /**
         * 设置当前活动任务 ID。
         */
        public Builder activeTaskId(String value) {
            activeTaskId = value;
            return this;
        }

        /**
         * 设置最近一次失败或停止原因。
         */
        public Builder error(String value) {
            error = value;
            return this;
        }

        /**
         * 设置已成功调整计划的次数。
         */
        public Builder revisionCount(int value) {
            revisionCount = value;
            return this;
        }

        /**
         * 设置最近一次调整原因。
         */
        public Builder lastRevisionReason(String value) {
            lastRevisionReason = value;
            return this;
        }

        /**
         * 设置计划创建时间。
         */
        public Builder createdAt(long value) {
            createdAt = value;
            return this;
        }

        /**
         * 设置最近更新时间。
         */
        public Builder updatedAt(long value) {
            updatedAt = value;
            return this;
        }

        /**
         * 设置计划完成时间。
         */
        public Builder completedAt(long value) {
            completedAt = value;
            return this;
        }

        /**
         * 校验计划标识、目标、状态和任务列表后创建不可变计划。
         */
        public AgentTaskPlan build() {
            if (!StringUtil.hasText(id) || !StringUtil.hasText(goal)
                || status == null || tasks == null || tasks.isEmpty() || revisionCount < 0) {
                throw new IllegalStateException("plan id, goal, status and tasks are required");
            }
            return new AgentTaskPlan(this);
        }
    }
}

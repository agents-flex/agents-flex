/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.task.AgentTaskPlan;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentRun 在稳定执行边界上的可持久化快照。
 *
 * <p>Snapshot 不包含 ChatModel、Tool、拦截器或 Java Throwable 等进程内对象。它保存恢复运行所需的
 * 数据，并通过 agentId 和 agentVersion 在恢复时重新绑定 {@link Agent}。phase、消息和
 * pendingToolCalls 用于区分“模型尚未决策”和“模型已经决策但工具尚未执行”。恢复时通过 Snapshot
 * 保存的 Agent 版本加载完整 Agent，再按 ToolCall 名称定位工具。</p>
 *
 * <p>有效执行策略、父子关系、调度时间、租约、审批结果、重试次数和预算用量都会随快照保存，使其他进程可以
 * 从同一个稳定边界继续执行。</p>
 */
public final class AgentRunSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 单次运行的稳定 ID，也是 Store 的主查询键。
     */
    private final String runId;
    /**
     * 恢复运行时交给 AgentLoader 的 Agent ID。
     */
    private final String agentId;
    /**
     * 恢复运行时绑定的 Agent 配置版本。
     */
    private final String agentVersion;
    /**
     * Run 创建时冻结的有效执行策略。
     */
    private final AgentExecutionPolicy executionPolicy;
    /**
     * 当前生命周期状态。
     */
    private final AgentRunStatus status;
    /**
     * 下一步应调用模型还是继续处理待执行工具。
     */
    private final AgentRunPhase phase;
    /**
     * 已复制的完整持久化消息历史。
     */
    private final List<Message> messages;
    /**
     * 模型已经生成但尚未全部处理的 ToolCall。
     */
    private final List<ToolCall> pendingToolCalls;
    /**
     * 阻塞状态对应的恢复条件；非阻塞状态通常为空。
     */
    private final AgentSuspension suspension;
    /**
     * 已发起的模型调用次数。
     */
    private final int iterationCount;
    /**
     * Runner 已经推进的 step 次数。
     */
    private final int stepCount;
    /**
     * 根 Run 及已汇总子 Run 的累计输入 Token。
     */
    private final long inputTokens;
    /**
     * 根 Run 及已汇总子 Run 的累计输出 Token。
     */
    private final long outputTokens;
    /**
     * 模型报告的累计总 Token。
     */
    private final long totalTokens;
    /**
     * 已开始执行的业务工具调用数量。
     */
    private final int toolCallCount;
    /**
     * 当前 Run 已安排的自动重试次数。
     */
    private final int retryCount;
    /**
     * 预算终止时命中的预算字段名称。
     */
    private final String budgetExceededReason;
    /**
     * 当前执行租约的 Worker ID。
     */
    private final String leaseOwner;
    /**
     * 区分同名 Worker 不同领取批次的唯一租约令牌。
     */
    private final String leaseId;
    /**
     * 执行租约到期毫秒时间戳。
     */
    private final long leaseUntil;
    /**
     * 已处理审批的 ToolCall ID 与允许结果映射。
     */
    private final Map<String, Boolean> toolApprovals;
    /**
     * 与当前根 Run 同版本保存的任务计划。
     */
    private final AgentTaskPlan taskPlan;
    /**
     * 当前 Run 是否允许模型使用内置规划工具。
     */
    private final boolean planningEnabled;
    /**
     * 当前 Run 在规划父子树中的深度，根 Run 为 0。
     */
    private final int planningDepth;
    /**
     * 是否已经收到协作式取消请求。
     */
    private final boolean cancellationRequested;
    /**
     * RUN_STARTED 生命周期事件是否已经发送。
     */
    private final boolean started;
    /**
     * 正常完成时的最终 AI 消息。
     */
    private final AiMessage finalMessage;
    /**
     * 失败异常的 Java 类型名称。
     */
    private final String errorType;
    /**
     * 失败异常的可持久化文本信息。
     */
    private final String errorMessage;
    /**
     * Run 创建时的毫秒时间戳。
     */
    private final long createdAt;
    /**
     * Run 进入终止状态的毫秒时间戳。
     */
    private final long completedAt;
    /**
     * Store 最近一次保存快照的毫秒时间戳。
     */
    private final long updatedAt;
    /**
     * 自动重试等延迟任务的最早可运行时间。
     */
    private final long nextRunAt;
    /**
     * Store 乐观锁版本，首次保存前为 -1。
     */
    private final long version;
    /**
     * 直接父 Run ID；根 Run 为空。
     */
    private final String parentRunId;
    /**
     * 父子运行树的根 Run ID。
     */
    private final String rootRunId;
    /**
     * 调用方附加并随 Snapshot 保存的业务元数据。
     */
    private final Map<String, Object> metadata;

    private AgentRunSnapshot(Builder builder) {
        this.runId = builder.runId;
        this.agentId = builder.agentId;
        this.agentVersion = builder.agentVersion;
        this.executionPolicy = builder.executionPolicy;
        this.status = builder.status;
        this.phase = builder.phase;
        this.messages = Collections.unmodifiableList(AgentMessageUtils.copyMessages(builder.messages));
        this.pendingToolCalls = Collections.unmodifiableList(AgentMessageUtils.copyToolCalls(builder.pendingToolCalls));
        this.suspension = builder.suspension == null ? null : builder.suspension.copy();
        this.iterationCount = builder.iterationCount;
        this.stepCount = builder.stepCount;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.totalTokens = builder.totalTokens;
        this.toolCallCount = builder.toolCallCount;
        this.retryCount = builder.retryCount;
        this.budgetExceededReason = builder.budgetExceededReason;
        this.leaseOwner = builder.leaseOwner;
        this.leaseId = builder.leaseId;
        this.leaseUntil = builder.leaseUntil;
        this.toolApprovals = builder.toolApprovals == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.toolApprovals));
        this.taskPlan = builder.taskPlan == null ? null : builder.taskPlan.copy();
        this.planningEnabled = builder.planningEnabled;
        this.planningDepth = builder.planningDepth;
        this.cancellationRequested = builder.cancellationRequested;
        this.started = builder.started;
        this.finalMessage = builder.finalMessage == null ? null : builder.finalMessage.copy();
        this.errorType = builder.errorType;
        this.errorMessage = builder.errorMessage;
        this.createdAt = builder.createdAt;
        this.completedAt = builder.completedAt;
        this.updatedAt = builder.updatedAt;
        this.nextRunAt = builder.nextRunAt;
        this.version = builder.version;
        this.parentRunId = builder.parentRunId;
        this.rootRunId = builder.rootRunId;
        this.metadata = builder.metadata == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.metadata));
    }

    /**
     * 创建包含必要运行标识的 Snapshot 构建器。
     */
    public static Builder builder(String runId, String agentId, String agentVersion) {
        return new Builder(runId, agentId, agentVersion);
    }

    /**
     * @return 与当前快照完全隔离的深拷贝
     */
    public AgentRunSnapshot copy() {
        return toBuilder().build();
    }

    /**
     * 返回内容相同、仅替换 Store 乐观锁版本的新快照。
     */
    public AgentRunSnapshot withVersion(long version) {
        return toBuilder().version(version).build();
    }

    /**
     * 返回已填入当前全部字段的构建器。
     */
    public Builder toBuilder() {
        return new Builder(runId, agentId, agentVersion)
            .executionPolicy(executionPolicy)
            .status(status)
            .phase(phase)
            .messages(messages)
            .pendingToolCalls(pendingToolCalls)
            .suspension(suspension)
            .iterationCount(iterationCount)
            .stepCount(stepCount)
            .inputTokens(inputTokens)
            .outputTokens(outputTokens)
            .totalTokens(totalTokens)
            .toolCallCount(toolCallCount)
            .retryCount(retryCount)
            .budgetExceededReason(budgetExceededReason)
            .leaseOwner(leaseOwner)
            .leaseId(leaseId)
            .leaseUntil(leaseUntil)
            .toolApprovals(toolApprovals)
            .taskPlan(taskPlan)
            .planningEnabled(planningEnabled)
            .planningDepth(planningDepth)
            .cancellationRequested(cancellationRequested)
            .started(started)
            .finalMessage(finalMessage)
            .error(errorType, errorMessage)
            .createdAt(createdAt)
            .completedAt(completedAt)
            .updatedAt(updatedAt)
            .nextRunAt(nextRunAt)
            .version(version)
            .parentRunId(parentRunId)
            .rootRunId(rootRunId)
            .metadata(metadata);
    }

    /**
     * @return 单次运行 ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * @return 绑定的 Agent ID
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * @return 绑定的 Agent 配置版本
     */
    public String getAgentVersion() {
        return agentVersion;
    }

    /**
     * @return Run 创建时冻结的执行策略
     */
    public AgentExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    /**
     * @return 当前运行状态
     */
    public AgentRunStatus getStatus() {
        return status;
    }

    /**
     * @return 下一步执行阶段
     */
    public AgentRunPhase getPhase() {
        return phase;
    }

    /**
     * @return 与内部状态隔离的消息列表
     */
    public List<Message> getMessages() {
        return Collections.unmodifiableList(AgentMessageUtils.copyMessages(messages));
    }

    /**
     * @return 与内部状态隔离的待执行 ToolCall 列表
     */
    public List<ToolCall> getPendingToolCalls() {
        return Collections.unmodifiableList(AgentMessageUtils.copyToolCalls(pendingToolCalls));
    }

    /**
     * @return 暂停信息副本；未阻塞时为 {@code null}
     */
    public AgentSuspension getSuspension() {
        return suspension == null ? null : suspension.copy();
    }

    /**
     * @return 已发起模型调用次数
     */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * @return Runner 已推进 step 次数
     */
    public int getStepCount() {
        return stepCount;
    }


    /**
     * @return 累计输入 Token
     */
    public long getInputTokens() {
        return inputTokens;
    }

    /**
     * @return 累计输出 Token
     */
    public long getOutputTokens() {
        return outputTokens;
    }

    /**
     * @return 模型报告的累计总 Token
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * @return 已执行的业务工具调用数量
     */
    public int getToolCallCount() {
        return toolCallCount;
    }

    /**
     * @return 已安排的自动重试次数
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * @return 预算终止原因字段；未超预算时为空
     */
    public String getBudgetExceededReason() {
        return budgetExceededReason;
    }

    /**
     * @return 当前执行租约的 Worker ID
     */
    public String getLeaseOwner() {
        return leaseOwner;
    }

    /**
     * @return 当前唯一租约令牌
     */
    public String getLeaseId() {
        return leaseId;
    }

    /**
     * @return 当前租约到期时间
     */
    public long getLeaseUntil() {
        return leaseUntil;
    }

    /**
     * @return 已处理的工具审批结果只读映射
     */
    public Map<String, Boolean> getToolApprovals() {
        return toolApprovals;
    }

    /**
     * @return 任务计划副本；尚未规划时为 {@code null}
     */
    public AgentTaskPlan getTaskPlan() {
        return taskPlan == null ? null : taskPlan.copy();
    }

    /**
     * @return 当前 Run 是否允许模型规划
     */
    public boolean isPlanningEnabled() {
        return planningEnabled;
    }

    /**
     * @return 当前 Run 的规划嵌套深度
     */
    public int getPlanningDepth() {
        return planningDepth;
    }

    /**
     * @return 是否已收到协作式取消请求
     */
    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    /**
     * @return 是否已经发送运行开始事件
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * @return 最终 AI 消息副本；未正常完成时为空
     */
    public AiMessage getFinalMessage() {
        return finalMessage == null ? null : finalMessage.copy();
    }

    /**
     * @return 持久化异常类型名称
     */
    public String getErrorType() {
        return errorType;
    }

    /**
     * @return 持久化异常消息
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * @return Run 创建时间
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * @return Run 结束时间；未结束时为 0
     */
    public long getCompletedAt() {
        return completedAt;
    }

    /**
     * @return Store 最近更新时间
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * @return 延迟任务最早可运行时间
     */
    public long getNextRunAt() {
        return nextRunAt;
    }

    /**
     * @return Store 乐观锁版本
     */
    public long getVersion() {
        return version;
    }

    /**
     * @return 直接父 Run ID；根 Run 为空
     */
    public String getParentRunId() {
        return parentRunId;
    }

    /**
     * @return 父子运行树根 Run ID
     */
    public String getRootRunId() {
        return rootRunId;
    }

    /**
     * @return 不可修改的业务元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Snapshot 构建器，供 Store、Codec 和数据库映射设置字段。
     */
    public static final class Builder {

        private final String runId;
        private final String agentId;
        private final String agentVersion;
        private AgentExecutionPolicy executionPolicy;
        private AgentRunStatus status = AgentRunStatus.READY;
        private AgentRunPhase phase = AgentRunPhase.MODEL;
        private List<Message> messages = Collections.emptyList();
        private List<ToolCall> pendingToolCalls = Collections.emptyList();
        private AgentSuspension suspension;
        private int iterationCount;
        private int stepCount;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private int toolCallCount;
        private int retryCount;
        private String budgetExceededReason;
        private String leaseOwner;
        private String leaseId;
        private long leaseUntil;
        private Map<String, Boolean> toolApprovals;
        private AgentTaskPlan taskPlan;
        private boolean planningEnabled;
        private int planningDepth;
        private boolean cancellationRequested;
        private boolean started;
        private AiMessage finalMessage;
        private String errorType;
        private String errorMessage;
        private long createdAt;
        private long completedAt;
        private long updatedAt;
        private long nextRunAt;
        private long version = -1;
        private String parentRunId;
        private String rootRunId;
        private Map<String, Object> metadata;

        private Builder(String runId, String agentId, String agentVersion) {
            if (runId == null || agentId == null || agentVersion == null) {
                throw new IllegalArgumentException(
                    "runId, agentId and agentVersion must not be null");
            }
            this.runId = runId;
            this.agentId = agentId;
            this.agentVersion = agentVersion;
        }

        /**
         * 设置生命周期状态。
         */
        public Builder status(AgentRunStatus status) {
            this.status = status;
            return this;
        }

        /**
         * 设置 Run 创建时冻结的执行策略。
         */
        public Builder executionPolicy(AgentExecutionPolicy value) {
            this.executionPolicy = value;
            return this;
        }

        /**
         * 设置下一步执行阶段。
         */
        public Builder phase(AgentRunPhase phase) {
            this.phase = phase;
            return this;
        }

        /**
         * 设置完整消息历史并立即复制输入列表。
         */
        public Builder messages(List<? extends Message> messages) {
            this.messages = AgentMessageUtils.copyMessages(messages);
            return this;
        }

        /**
         * 设置尚未处理完成的 ToolCall 并立即复制输入列表。
         */
        public Builder pendingToolCalls(List<ToolCall> calls) {
            this.pendingToolCalls = AgentMessageUtils.copyToolCalls(calls);
            return this;
        }

        /**
         * 设置阻塞恢复条件。
         */
        public Builder suspension(AgentSuspension suspension) {
            this.suspension = suspension;
            return this;
        }

        /**
         * 设置已发起的模型调用次数。
         */
        public Builder iterationCount(int value) {
            this.iterationCount = value;
            return this;
        }

        /**
         * 设置 Runner 已推进 step 次数。
         */
        public Builder stepCount(int value) {
            this.stepCount = value;
            return this;
        }

        /**
         * 设置累计输入 Token。
         */
        public Builder inputTokens(long value) {
            this.inputTokens = value;
            return this;
        }

        /**
         * 设置累计输出 Token。
         */
        public Builder outputTokens(long value) {
            this.outputTokens = value;
            return this;
        }

        /**
         * 设置累计总 Token。
         */
        public Builder totalTokens(long value) {
            this.totalTokens = value;
            return this;
        }

        /**
         * 设置已执行的业务工具调用数。
         */
        public Builder toolCallCount(int value) {
            this.toolCallCount = value;
            return this;
        }

        /**
         * 设置已安排的自动重试次数。
         */
        public Builder retryCount(int value) {
            this.retryCount = value;
            return this;
        }

        /**
         * 设置预算终止原因字段。
         */
        public Builder budgetExceededReason(String value) {
            this.budgetExceededReason = value;
            return this;
        }

        /**
         * 设置租约 Worker ID。
         */
        public Builder leaseOwner(String value) {
            this.leaseOwner = value;
            return this;
        }

        /**
         * 设置唯一租约令牌。
         */
        public Builder leaseId(String value) {
            this.leaseId = value;
            return this;
        }

        /**
         * 设置租约到期时间。
         */
        public Builder leaseUntil(long value) {
            this.leaseUntil = value;
            return this;
        }

        /**
         * 设置已处理工具审批结果。
         */
        public Builder toolApprovals(Map<String, Boolean> value) {
            this.toolApprovals = value;
            return this;
        }

        /**
         * 设置与 Run 同版本保存的任务计划。
         */
        public Builder taskPlan(AgentTaskPlan value) {
            this.taskPlan = value;
            return this;
        }

        /**
         * 设置当前 Run 是否开放规划工具。
         */
        public Builder planningEnabled(boolean value) {
            this.planningEnabled = value;
            return this;
        }

        /**
         * 设置规划父子树深度。
         */
        public Builder planningDepth(int value) {
            this.planningDepth = value;
            return this;
        }

        /**
         * 设置协作式取消标记。
         */
        public Builder cancellationRequested(boolean value) {
            this.cancellationRequested = value;
            return this;
        }

        /**
         * 设置运行开始事件是否已经发送。
         */
        public Builder started(boolean value) {
            this.started = value;
            return this;
        }

        /**
         * 设置正常完成时的最终消息。
         */
        public Builder finalMessage(AiMessage value) {
            this.finalMessage = value;
            return this;
        }

        /**
         * 设置可持久化的异常类型和消息。
         */
        public Builder error(String type, String message) {
            this.errorType = type;
            this.errorMessage = message;
            return this;
        }

        /**
         * 设置 Run 创建时间。
         */
        public Builder createdAt(long value) {
            this.createdAt = value;
            return this;
        }

        /**
         * 设置 Run 结束时间。
         */
        public Builder completedAt(long value) {
            this.completedAt = value;
            return this;
        }

        /**
         * 设置 Store 最近更新时间。
         */
        public Builder updatedAt(long value) {
            this.updatedAt = value;
            return this;
        }

        /**
         * 设置延迟任务最早可运行时间。
         */
        public Builder nextRunAt(long value) {
            this.nextRunAt = value;
            return this;
        }

        /**
         * 设置 Store 乐观锁版本。
         */
        public Builder version(long value) {
            this.version = value;
            return this;
        }

        /**
         * 设置直接父 Run ID。
         */
        public Builder parentRunId(String value) {
            this.parentRunId = value;
            return this;
        }

        /**
         * 设置父子运行树根 Run ID。
         */
        public Builder rootRunId(String value) {
            this.rootRunId = value;
            return this;
        }

        /**
         * 设置随 Snapshot 保存的业务元数据。
         */
        public Builder metadata(Map<String, Object> value) {
            this.metadata = value;
            return this;
        }

        /**
         * 校验恢复所需的 Agent、策略和状态后创建快照。
         */
        public AgentRunSnapshot build() {
            if (!StringUtil.hasText(agentVersion)) {
                throw new IllegalStateException("agentVersion must not be blank");
            }
            if (executionPolicy == null) {
                throw new IllegalStateException("executionPolicy must not be null");
            }
            if (status == null) {
                throw new IllegalStateException("status must not be null");
            }
            if (phase == null) {
                throw new IllegalStateException("phase must not be null");
            }
            return new AgentRunSnapshot(this);
        }
    }
}

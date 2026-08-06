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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentTurn 与 AgentTurnSnapshot 共享的可序列化轮次状态。
 *
 * <p>执行中的 {@link AgentTurn} 持有可变 State，由同包内的状态转换方法推进；
 * {@link AgentTurnSnapshot} 持有深拷贝后的不可变 State。消息、ToolCall、Suspension、任务计划和
 * 最终消息在复制时都会隔离，避免保存快照后继续运行的 Turn 修改已经持久化的内容。</p>
 *
 * <p>该类不保存 Agent、Prompt、Throwable、ChatModel、Tool 或其他进程内对象。新增可持久化字段时
 * 应优先放在这里，使 Turn 和 Snapshot 不再分别维护一套镜像字段。</p>
 */
public final class AgentTurnState implements Serializable {

    private static final long serialVersionUID = 1L;

    private String turnId;
    private AgentExecutionPolicy executionPolicy;
    private AgentTurnStatus status = AgentTurnStatus.READY;
    private AgentTurnPhase phase = AgentTurnPhase.MODEL;
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
    private volatile String leaseOwner;
    private volatile String leaseId;
    private volatile long leaseUntil;
    private Map<String, Boolean> toolApprovals = Collections.emptyMap();
    private Map<String, Map<String, Object>> toolInputData = Collections.emptyMap();
    private AgentTaskPlan taskPlan;
    private boolean planningEnabled;
    private int planningDepth;
    private volatile boolean cancellationRequested;
    private boolean started;
    private AiMessage finalMessage;
    private String errorType;
    private String errorMessage;
    private long createdAt;
    private long completedAt;
    private long updatedAt;
    private long nextRunnableAt;
    private long version = -1;
    private String parentTurnId;
    private String rootTurnId;
    private Map<String, Object> metadata = Collections.emptyMap();
    private boolean immutable;

    AgentTurnState(String turnId, AgentExecutionPolicy executionPolicy, long createdAt) {
        if (turnId == null) throw new IllegalArgumentException("turnId must not be null");
        this.turnId = turnId;
        this.executionPolicy = executionPolicy;
        this.createdAt = createdAt;
        this.rootTurnId = turnId;
        this.messages = new ArrayList<>();
        this.pendingToolCalls = new ArrayList<>();
        this.toolApprovals = new HashMap<>();
        this.toolInputData = new HashMap<>();
        this.metadata = new HashMap<>();
    }

    /**
     * 仅供字段模式反序列化使用。
     */
    private AgentTurnState() {
    }

    private AgentTurnState(AgentTurnState source, boolean immutable) {
        this.turnId = source.turnId;
        this.executionPolicy = source.executionPolicy;
        this.status = source.status;
        this.phase = source.phase;
        this.messages = immutable ? Collections.unmodifiableList(AgentMessageUtils.copyMessages(source.messages)) : AgentMessageUtils.copyMessages(source.messages);
        this.pendingToolCalls = immutable ? Collections.unmodifiableList(AgentMessageUtils.copyToolCalls(source.pendingToolCalls)) : AgentMessageUtils.copyToolCalls(source.pendingToolCalls);
        this.suspension = source.suspension == null ? null : source.suspension.copy();
        this.iterationCount = source.iterationCount;
        this.stepCount = source.stepCount;
        this.inputTokens = source.inputTokens;
        this.outputTokens = source.outputTokens;
        this.totalTokens = source.totalTokens;
        this.toolCallCount = source.toolCallCount;
        this.retryCount = source.retryCount;
        this.budgetExceededReason = source.budgetExceededReason;
        this.leaseOwner = source.leaseOwner;
        this.leaseId = source.leaseId;
        this.leaseUntil = source.leaseUntil;
        Map<String, Boolean> approvals = new HashMap<>(source.toolApprovals);
        this.toolApprovals = immutable ? Collections.unmodifiableMap(approvals) : approvals;
        this.toolInputData = copyToolInputData(source.toolInputData, immutable);
        this.taskPlan = source.taskPlan == null ? null : source.taskPlan.copy();
        this.planningEnabled = source.planningEnabled;
        this.planningDepth = source.planningDepth;
        this.cancellationRequested = source.cancellationRequested;
        this.started = source.started;
        this.finalMessage = source.finalMessage == null ? null : source.finalMessage.copy();
        this.errorType = source.errorType;
        this.errorMessage = source.errorMessage;
        this.createdAt = source.createdAt;
        this.completedAt = source.completedAt;
        this.updatedAt = source.updatedAt;
        this.nextRunnableAt = source.nextRunnableAt;
        this.version = source.version;
        this.parentTurnId = source.parentTurnId;
        this.rootTurnId = source.rootTurnId;
        Map<String, Object> metadataCopy = new HashMap<>(source.metadata);
        this.metadata = immutable ? Collections.unmodifiableMap(metadataCopy) : metadataCopy;
        this.immutable = immutable;
    }

    AgentTurnState mutableCopy() {
        return new AgentTurnState(this, false);
    }

    AgentTurnState immutableCopy() {
        return new AgentTurnState(this, true);
    }

    /**
     * 创建一份新的运行状态构建器。
     *
     * <p>该入口主要供 Store、序列化适配器和测试构造持久化状态。Agent 正常执行时仍由
     * {@link AgentTurn} 内部推进可变 State。</p>
     */
    public static Builder builder(String turnId, AgentExecutionPolicy executionPolicy,
                                  long createdAt) {
        return new Builder(new AgentTurnState(turnId, executionPolicy, createdAt));
    }

    /**
     * 返回包含当前全部字段的构建器，用于生成修改后的不可变状态。
     */
    public Builder toBuilder() {
        return new Builder(mutableCopy());
    }

    /**
     * @return Turn ID
     */
    public String getTurnId() {
        return turnId;
    }

    /**
     * @return Turn 创建时冻结的执行策略
     */
    public AgentExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    /**
     * @return 当前生命周期状态
     */
    public AgentTurnStatus getStatus() {
        return status;
    }

    /**
     * @return 下一步执行阶段
     */
    public AgentTurnPhase getPhase() {
        return phase;
    }

    /**
     * @return 与内部状态隔离的完整消息历史
     */
    public List<Message> getMessages() {
        return Collections.unmodifiableList(AgentMessageUtils.copyMessages(messages));
    }

    /**
     * @return 与内部状态隔离的待执行工具调用
     */
    public List<ToolCall> getPendingToolCalls() {
        return Collections.unmodifiableList(AgentMessageUtils.copyToolCalls(pendingToolCalls));
    }

    /**
     * @return 阻塞恢复信息副本
     */
    public AgentSuspension getSuspension() {
        return suspension == null ? null : suspension.copy();
    }

    /**
     * @return 已发起的模型调用次数
     */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * @return Runner 已推进的 step 次数
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
     * @return 已开始执行的业务工具调用数
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
     * @return 预算终止时命中的限制名称
     */
    public String getBudgetExceededReason() {
        return budgetExceededReason;
    }

    /**
     * @return 当前租约 Worker ID
     */
    public String getLeaseOwner() {
        return leaseOwner;
    }

    /**
     * @return 当前领取批次的租约令牌
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
     * @return 不可修改的工具审批结果
     */
    public Map<String, Boolean> getToolApprovals() {
        return Collections.unmodifiableMap(toolApprovals);
    }

    /**
     * @return 按 ToolCall ID 保存的工具表单提交数据
     */
    public Map<String, Map<String, Object>> getToolInputData() {
        return copyToolInputData(toolInputData, true);
    }

    /**
     * @return 任务计划副本
     */
    public AgentTaskPlan getTaskPlan() {
        return taskPlan == null ? null : taskPlan.copy();
    }

    /**
     * @return 是否向模型开放规划工具
     */
    public boolean isPlanningEnabled() {
        return planningEnabled;
    }

    /**
     * @return 当前 Turn 在规划父子树中的深度
     */
    public int getPlanningDepth() {
        return planningDepth;
    }

    /**
     * @return 是否收到协作式取消请求
     */
    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    /**
     * @return TURN_STARTED 事件是否已经发送
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * @return 最终 AI 消息副本
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
     * @return 持久化异常文本
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * @return Turn 创建时间
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * @return Turn 结束时间，未结束时为 0
     */
    public long getCompletedAt() {
        return completedAt;
    }

    /**
     * @return Store 最近保存时间
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * @return 延迟任务最早可运行时间
     */
    public long getNextRunnableAt() {
        return nextRunnableAt;
    }

    /**
     * @return Store 乐观锁版本
     */
    public long getVersion() {
        return version;
    }

    /**
     * @return 直接父 Turn ID
     */
    public String getParentTurnId() {
        return parentTurnId;
    }

    /**
     * @return Turn 树的根 Turn ID
     */
    public String getRootTurnId() {
        return rootTurnId;
    }

    /**
     * @return 不可修改的业务元数据
     */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * @return 当前对象是否为 Snapshot 持有的不可变副本
     */
    public boolean isImmutable() {
        return immutable;
    }

    private void requireMutable() {
        if (immutable) throw new UnsupportedOperationException("AgentTurnState is immutable");
    }

    void setExecutionPolicy(AgentExecutionPolicy value) {
        requireMutable();
        executionPolicy = value;
    }

    void setStatus(AgentTurnStatus value) {
        requireMutable();
        status = value;
    }

    void setPhase(AgentTurnPhase value) {
        requireMutable();
        phase = value;
    }

    void setMessages(List<? extends Message> value) {
        requireMutable();
        messages = AgentMessageUtils.copyMessages(value);
    }

    void setPendingToolCalls(List<ToolCall> value) {
        requireMutable();
        pendingToolCalls = AgentMessageUtils.copyToolCalls(value);
    }

    void clearPendingToolCalls() {
        requireMutable();
        pendingToolCalls.clear();
    }

    void removeFirstPendingToolCall() {
        requireMutable();
        if (!pendingToolCalls.isEmpty()) pendingToolCalls.remove(0);
    }

    void setSuspension(AgentSuspension value) {
        requireMutable();
        suspension = value == null ? null : value.copy();
    }

    void setIterationCount(int value) {
        requireMutable();
        iterationCount = value;
    }

    void incrementIterationCount() {
        requireMutable();
        iterationCount++;
    }

    void setStepCount(int value) {
        requireMutable();
        stepCount = value;
    }

    void incrementStepCount() {
        requireMutable();
        stepCount++;
    }

    void setInputTokens(long value) {
        requireMutable();
        inputTokens = value;
    }

    void setOutputTokens(long value) {
        requireMutable();
        outputTokens = value;
    }

    void setTotalTokens(long value) {
        requireMutable();
        totalTokens = value;
    }

    void addUsage(long input, long output, long total) {
        requireMutable();
        inputTokens += input;
        outputTokens += output;
        totalTokens += total;
    }

    void setToolCallCount(int value) {
        requireMutable();
        toolCallCount = value;
    }

    void incrementToolCallCount() {
        requireMutable();
        toolCallCount++;
    }

    void rollbackToolCallCount() {
        requireMutable();
        if (toolCallCount > 0) toolCallCount--;
    }

    void setRetryCount(int value) {
        requireMutable();
        retryCount = value;
    }

    void incrementRetryCount() {
        requireMutable();
        retryCount++;
    }

    void addChildUsage(AgentTurnState child) {
        requireMutable();
        inputTokens += child.inputTokens;
        outputTokens += child.outputTokens;
        totalTokens += child.totalTokens;
        toolCallCount += child.toolCallCount;
        retryCount += child.retryCount;
    }

    void setBudgetExceededReason(String value) {
        requireMutable();
        budgetExceededReason = value;
    }

    void setLeaseOwner(String value) {
        requireMutable();
        leaseOwner = value;
    }

    void setLeaseId(String value) {
        requireMutable();
        leaseId = value;
    }

    void setLeaseUntil(long value) {
        requireMutable();
        leaseUntil = value;
    }

    void setToolApprovals(Map<String, Boolean> value) {
        requireMutable();
        toolApprovals = value == null ? new HashMap<>() : new HashMap<>(value);
    }

    void approveTool(String callId, boolean approved) {
        requireMutable();
        toolApprovals.put(callId, approved);
    }

    Boolean getToolApproval(String callId) {
        return toolApprovals.get(callId);
    }

    void setToolInputData(Map<String, Map<String, Object>> value) {
        requireMutable();
        toolInputData = copyToolInputData(value, false);
    }

    void putToolInputData(String callId, Map<String, ?> value) {
        requireMutable();
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolInputData == null) toolInputData = new HashMap<>();
        toolInputData.put(callId, value == null
            ? new HashMap<String, Object>() : new HashMap<String, Object>(value));
    }

    Map<String, Object> getToolInputData(String callId) {
        if (toolInputData == null) return Collections.emptyMap();
        Map<String, Object> value = toolInputData.get(callId);
        return value == null ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(value));
    }

    void setTaskPlan(AgentTaskPlan value) {
        requireMutable();
        taskPlan = value == null ? null : value.copy();
    }

    void setPlanningEnabled(boolean value) {
        requireMutable();
        planningEnabled = value;
    }

    void setPlanningDepth(int value) {
        requireMutable();
        planningDepth = value;
    }

    void setCancellationRequested(boolean value) {
        requireMutable();
        cancellationRequested = value;
    }

    void setStarted(boolean value) {
        requireMutable();
        started = value;
    }

    void setFinalMessage(AiMessage value) {
        requireMutable();
        finalMessage = value == null ? null : value.copy();
    }

    void setError(String type, String message) {
        requireMutable();
        errorType = type;
        errorMessage = message;
    }

    void setCreatedAt(long value) {
        requireMutable();
        createdAt = value;
    }

    void setCompletedAt(long value) {
        requireMutable();
        completedAt = value;
    }

    void setUpdatedAt(long value) {
        requireMutable();
        updatedAt = value;
    }

    void setNextRunnableAt(long value) {
        requireMutable();
        nextRunnableAt = value;
    }

    void setVersion(long value) {
        requireMutable();
        version = value;
    }

    void setParentTurnId(String value) {
        requireMutable();
        parentTurnId = value;
    }

    void setRootTurnId(String value) {
        requireMutable();
        rootTurnId = value;
    }

    void setMetadata(Map<String, Object> value) {
        requireMutable();
        metadata = value == null ? new HashMap<>() : new HashMap<>(value);
    }

    void putMetadata(String key, Object value) {
        requireMutable();
        metadata.put(key, value);
    }

    /**
     * AgentTurnState 的唯一字段级构建入口。
     *
     * <p>Builder 内部操作可变副本，{@link #build()} 返回深拷贝后的不可变状态。新增持久化字段时，
     * 只需在 State 及此 Builder 中维护，不应再在 AgentTurnSnapshot 中增加转发方法。</p>
     */
    public static final class Builder {
        private final AgentTurnState state;

        private Builder(AgentTurnState state) {
            this.state = state;
        }

        public Builder executionPolicy(AgentExecutionPolicy value) {
            state.setExecutionPolicy(value);
            return this;
        }

        public Builder status(AgentTurnStatus value) {
            state.setStatus(value);
            return this;
        }

        public Builder phase(AgentTurnPhase value) {
            state.setPhase(value);
            return this;
        }

        public Builder messages(List<? extends Message> value) {
            state.setMessages(value);
            return this;
        }

        public Builder pendingToolCalls(List<ToolCall> value) {
            state.setPendingToolCalls(value);
            return this;
        }

        public Builder suspension(AgentSuspension value) {
            state.setSuspension(value);
            return this;
        }

        public Builder iterationCount(int value) {
            state.setIterationCount(value);
            return this;
        }

        public Builder stepCount(int value) {
            state.setStepCount(value);
            return this;
        }

        public Builder inputTokens(long value) {
            state.setInputTokens(value);
            return this;
        }

        public Builder outputTokens(long value) {
            state.setOutputTokens(value);
            return this;
        }

        public Builder totalTokens(long value) {
            state.setTotalTokens(value);
            return this;
        }

        public Builder toolCallCount(int value) {
            state.setToolCallCount(value);
            return this;
        }

        public Builder retryCount(int value) {
            state.setRetryCount(value);
            return this;
        }

        public Builder budgetExceededReason(String value) {
            state.setBudgetExceededReason(value);
            return this;
        }

        public Builder leaseOwner(String value) {
            state.setLeaseOwner(value);
            return this;
        }

        public Builder leaseId(String value) {
            state.setLeaseId(value);
            return this;
        }

        public Builder leaseUntil(long value) {
            state.setLeaseUntil(value);
            return this;
        }

        public Builder toolApprovals(Map<String, Boolean> value) {
            state.setToolApprovals(value);
            return this;
        }

        public Builder toolInputData(Map<String, Map<String, Object>> value) {
            state.setToolInputData(value);
            return this;
        }

        public Builder taskPlan(AgentTaskPlan value) {
            state.setTaskPlan(value);
            return this;
        }

        public Builder planningEnabled(boolean value) {
            state.setPlanningEnabled(value);
            return this;
        }

        public Builder planningDepth(int value) {
            state.setPlanningDepth(value);
            return this;
        }

        public Builder cancellationRequested(boolean value) {
            state.setCancellationRequested(value);
            return this;
        }

        public Builder started(boolean value) {
            state.setStarted(value);
            return this;
        }

        public Builder finalMessage(AiMessage value) {
            state.setFinalMessage(value);
            return this;
        }

        public Builder error(String type, String message) {
            state.setError(type, message);
            return this;
        }

        public Builder createdAt(long value) {
            state.setCreatedAt(value);
            return this;
        }

        public Builder completedAt(long value) {
            state.setCompletedAt(value);
            return this;
        }

        public Builder updatedAt(long value) {
            state.setUpdatedAt(value);
            return this;
        }

        public Builder nextRunnableAt(long value) {
            state.setNextRunnableAt(value);
            return this;
        }

        public Builder version(long value) {
            state.setVersion(value);
            return this;
        }

        public Builder parentTurnId(String value) {
            state.setParentTurnId(value);
            return this;
        }

        public Builder rootTurnId(String value) {
            state.setRootTurnId(value);
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            state.setMetadata(value);
            return this;
        }

        public AgentTurnState build() {
            return state.immutableCopy();
        }
    }

    private static Map<String, Map<String, Object>> copyToolInputData(
        Map<String, Map<String, Object>> source, boolean immutable) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (source != null) {
            for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
                Map<String, Object> value = entry.getValue() == null
                    ? new HashMap<String, Object>() : new HashMap<>(entry.getValue());
                result.put(entry.getKey(), immutable
                    ? Collections.unmodifiableMap(value) : value);
            }
        }
        return immutable ? Collections.unmodifiableMap(result) : result;
    }
}

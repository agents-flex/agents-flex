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
 * AgentRun 与 AgentRunSnapshot 共享的可序列化运行状态。
 *
 * <p>运行中的 {@link AgentRun} 持有可变 State，由同包内的状态转换方法推进；
 * {@link AgentRunSnapshot} 持有深拷贝后的不可变 State。消息、ToolCall、Suspension、任务计划和
 * 最终消息在复制时都会隔离，避免保存快照后继续运行的 Run 修改已经持久化的内容。</p>
 *
 * <p>该类不保存 Agent、Prompt、Throwable、ChatModel、Tool 或其他进程内对象。新增可持久化字段时
 * 应优先放在这里，使 Run 和 Snapshot 不再分别维护一套镜像字段。</p>
 */
public final class AgentRunState implements Serializable {

    private static final long serialVersionUID = 1L;

    private String runId;
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
    private volatile String leaseOwner;
    private volatile String leaseId;
    private volatile long leaseUntil;
    private Map<String, Boolean> toolApprovals = Collections.emptyMap();
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
    private long nextRunAt;
    private long version = -1;
    private String parentRunId;
    private String rootRunId;
    private Map<String, Object> metadata = Collections.emptyMap();
    private boolean immutable;

    AgentRunState(String runId, AgentExecutionPolicy executionPolicy, long createdAt) {
        if (runId == null) throw new IllegalArgumentException("runId must not be null");
        this.runId = runId;
        this.executionPolicy = executionPolicy;
        this.createdAt = createdAt;
        this.rootRunId = runId;
        this.messages = new ArrayList<>();
        this.pendingToolCalls = new ArrayList<>();
        this.toolApprovals = new HashMap<>();
        this.metadata = new HashMap<>();
    }

    /**
     * 仅供字段模式反序列化使用。
     */
    private AgentRunState() {
    }

    private AgentRunState(AgentRunState source, boolean immutable) {
        this.runId = source.runId;
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
        this.nextRunAt = source.nextRunAt;
        this.version = source.version;
        this.parentRunId = source.parentRunId;
        this.rootRunId = source.rootRunId;
        Map<String, Object> metadataCopy = new HashMap<>(source.metadata);
        this.metadata = immutable ? Collections.unmodifiableMap(metadataCopy) : metadataCopy;
        this.immutable = immutable;
    }

    AgentRunState mutableCopy() {
        return new AgentRunState(this, false);
    }

    AgentRunState immutableCopy() {
        return new AgentRunState(this, true);
    }

    /**
     * @return 运行 ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * @return Run 创建时冻结的执行策略
     */
    public AgentExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    /**
     * @return 当前生命周期状态
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
     * @return 当前 Run 在规划父子树中的深度
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
     * @return RUN_STARTED 事件是否已经发送
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
     * @return Run 创建时间
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * @return Run 结束时间，未结束时为 0
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
     * @return 直接父 Run ID
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
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * @return 当前对象是否为 Snapshot 持有的不可变副本
     */
    public boolean isImmutable() {
        return immutable;
    }

    private void requireMutable() {
        if (immutable) throw new UnsupportedOperationException("AgentRunState is immutable");
    }

    void setExecutionPolicy(AgentExecutionPolicy value) {
        requireMutable();
        executionPolicy = value;
    }

    void setStatus(AgentRunStatus value) {
        requireMutable();
        status = value;
    }

    void setPhase(AgentRunPhase value) {
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

    void setRetryCount(int value) {
        requireMutable();
        retryCount = value;
    }

    void incrementRetryCount() {
        requireMutable();
        retryCount++;
    }

    void addChildUsage(AgentRunState child) {
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

    void setNextRunAt(long value) {
        requireMutable();
        nextRunAt = value;
    }

    void setVersion(long value) {
        requireMutable();
        version = value;
    }

    void setParentRunId(String value) {
        requireMutable();
        parentRunId = value;
    }

    void setRootRunId(String value) {
        requireMutable();
        rootRunId = value;
    }

    void setMetadata(Map<String, Object> value) {
        requireMutable();
        metadata = value == null ? new HashMap<>() : new HashMap<>(value);
    }

    void putMetadata(String key, Object value) {
        requireMutable();
        metadata.put(key, value);
    }
}

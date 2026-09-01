/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.agent.tool.AgentToolResumeInfo;

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
 * {@link AgentTurnSnapshot} 持有深拷贝后的不可变 State。消息、ToolCall 和 Suspension、
 * 最终消息在复制时都会隔离，避免保存快照后继续运行的 Turn 修改已经持久化的内容。</p>
 *
 * <p>该类不保存 Agent、Prompt、Throwable、ChatModel、Tool 或其他进程内对象。新增可持久化字段时
 * 应优先放在这里，使 Turn 和 Snapshot 不再分别维护一套镜像字段。</p>
 */
public final class AgentTurnState implements Serializable {

    private static final long serialVersionUID = 1L;

    private String turnId;
    private AgentExecutionPolicy executionPolicy;
    /**
     * 当前 Turn 是否使用模型流式调用；随 Snapshot 持久化，恢复后保持一致。
     */
    private boolean streaming;
    private AgentTurnStatus status = AgentTurnStatus.READY;
    private AgentTurnExecutionPoint executionPoint = AgentTurnExecutionPoint.INVOKE_MODEL;
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
    /** 按 ToolCall ID 记录实际进入 Tool 函数的次数。 */
    private Map<String, Integer> toolExecutionAttempts = Collections.emptyMap();
    /** 按 ToolCall ID 记录本次执行前最近一次恢复来源。 */
    private Map<String, AgentToolResumeInfo> toolResumeInfo = Collections.emptyMap();
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
    private Map<String, Object> metadata = Collections.emptyMap();
    private boolean immutable;

    /**
     * 创建供运行中 Turn 持有的可变初始状态，并初始化所有集合字段。
     *
     * @param turnId          全局唯一 Turn ID
     * @param executionPolicy 创建时冻结的执行策略
     * @param createdAt       创建时间戳（毫秒）
     */
    AgentTurnState(String turnId, AgentExecutionPolicy executionPolicy, long createdAt) {
        if (turnId == null) throw new IllegalArgumentException("turnId must not be null");
        this.turnId = turnId;
        this.executionPolicy = executionPolicy;
        this.createdAt = createdAt;
        this.messages = new ArrayList<>();
        this.pendingToolCalls = new ArrayList<>();
        this.toolApprovals = new HashMap<>();
        this.toolInputData = new HashMap<>();
        this.toolExecutionAttempts = new HashMap<>();
        this.toolResumeInfo = new HashMap<>();
        this.metadata = new HashMap<>();
    }

    /**
     * 仅供字段模式反序列化使用。
     */
    private AgentTurnState() {
    }

    /**
     * 深复制另一个状态，并按用途决定集合是否暴露为不可修改视图。
     *
     * @param source    复制来源
     * @param immutable {@code true} 表示生成 Snapshot 使用的不可变状态
     */
    private AgentTurnState(AgentTurnState source, boolean immutable) {
        this.turnId = source.turnId;
        this.executionPolicy = source.executionPolicy;
        this.streaming = source.streaming;
        this.status = source.status;
        this.executionPoint = source.getExecutionPoint();
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
        Map<String, Integer> attempts = source.toolExecutionAttempts == null
            ? new HashMap<String, Integer>() : new HashMap<>(source.toolExecutionAttempts);
        this.toolExecutionAttempts = immutable
            ? Collections.unmodifiableMap(attempts) : attempts;
        Map<String, AgentToolResumeInfo> resumes = source.toolResumeInfo == null
            ? new HashMap<String, AgentToolResumeInfo>() : new HashMap<>(source.toolResumeInfo);
        this.toolResumeInfo = immutable
            ? Collections.unmodifiableMap(resumes) : resumes;
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
        Map<String, Object> metadataCopy = new HashMap<>(source.metadata);
        this.metadata = immutable ? Collections.unmodifiableMap(metadataCopy) : metadataCopy;
        this.immutable = immutable;
    }

    /**
     * 创建可继续执行状态转换的深复制。
     *
     * @return 集合和消息均与当前对象隔离的可变状态
     */
    AgentTurnState mutableCopy() {
        return new AgentTurnState(this, false);
    }

    /**
     * 创建适合持久化 Snapshot 的深复制。
     *
     * @return 所有集合均不可修改、嵌套消息已隔离的状态
     */
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
     * @return 当前 Turn 是否使用流式模型调用
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * @return 当前生命周期状态
     */
    public AgentTurnStatus getStatus() {
        return status;
    }

    /**
     * @return 下一步执行入口
     */
    public AgentTurnExecutionPoint getExecutionPoint() {
        return executionPoint;
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

    /** @return 每个 ToolCall 实际进入工具函数的累计次数 */
    public Map<String, Integer> getToolExecutionAttempts() {
        return Collections.unmodifiableMap(toolExecutionAttempts == null
            ? Collections.<String, Integer>emptyMap() : toolExecutionAttempts);
    }

    /** @return 每个 ToolCall 最近一次恢复来源 */
    public Map<String, AgentToolResumeInfo> getToolResumeInfo() {
        return Collections.unmodifiableMap(toolResumeInfo == null
            ? Collections.<String, AgentToolResumeInfo>emptyMap() : toolResumeInfo);
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

    /**
     * 拒绝对 Snapshot 持有的不可变状态执行任何写操作。
     *
     * @throws UnsupportedOperationException 当前状态不可变时抛出
     */
    private void requireMutable() {
        if (immutable) throw new UnsupportedOperationException("AgentTurnState is immutable");
    }

    void setExecutionPolicy(AgentExecutionPolicy value) {
        requireMutable();
        executionPolicy = value;
    }

    void setStreaming(boolean value) {
        requireMutable();
        streaming = value;
    }

    void setStatus(AgentTurnStatus value) {
        requireMutable();
        status = value;
    }

    void setExecutionPoint(AgentTurnExecutionPoint value) {
        requireMutable();
        executionPoint = value;
    }

    void setMessages(List<? extends Message> value) {
        requireMutable();
        messages = AgentMessageUtils.copyMessages(value);
    }

    void setPendingToolCalls(List<ToolCall> value) {
        requireMutable();
        pendingToolCalls = AgentMessageUtils.copyToolCalls(value);
    }

    /**
     * 清空尚未执行的工具调用；仅允许用于运行态可变副本。
     */
    void clearPendingToolCalls() {
        requireMutable();
        pendingToolCalls.clear();
    }

    /**
     * 按模型声明顺序移除首个待执行工具调用；列表为空时不做处理。
     */
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

    /**
     * 在一次模型调用正式开始前递增模型迭代计数。
     */
    void incrementIterationCount() {
        requireMutable();
        iterationCount++;
    }

    void setStepCount(int value) {
        requireMutable();
        stepCount = value;
    }

    /**
     * 在 Runner 接受一个新步骤时递增步骤计数。
     */
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

    /**
     * 累加一次模型响应报告的 Token 用量。
     *
     * @param input  输入 Token 数
     * @param output 输出 Token 数
     * @param total  总 Token 数
     */
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

    /**
     * 在工具调用通过执行前检查后递增累计工具次数。
     */
    void incrementToolCallCount() {
        requireMutable();
        toolCallCount++;
    }

    /**
     * 回滚尚未真正执行的工具计数，最小保持为零。
     *
     * <p>主要用于审批或用户输入导致工具在执行前挂起的路径。</p>
     */
    void rollbackToolCallCount() {
        requireMutable();
        if (toolCallCount > 0) toolCallCount--;
    }

    void setRetryCount(int value) {
        requireMutable();
        retryCount = value;
    }

    /**
     * 在一次可重试失败被调度后递增累计重试次数。
     */
    void incrementRetryCount() {
        requireMutable();
        retryCount++;
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

    /**
     * 记录指定工具调用的人工审批结论，供恢复后的执行阶段消费。
     *
     * @param callId   工具调用 ID
     * @param approved {@code true} 表示批准，{@code false} 表示拒绝
     */
    void approveTool(String callId, boolean approved) {
        requireMutable();
        toolApprovals.put(callId, approved);
    }

    /**
     * 查询工具调用已经记录的审批结论。
     *
     * @param callId 工具调用 ID
     * @return 批准或拒绝；尚未审批时返回 {@code null}
     */
    Boolean getToolApproval(String callId) {
        return toolApprovals.get(callId);
    }

    void setToolInputData(Map<String, Map<String, Object>> value) {
        requireMutable();
        toolInputData = copyToolInputData(value, false);
    }

    void setToolExecutionAttempts(Map<String, Integer> value) {
        requireMutable();
        toolExecutionAttempts = value == null ? new HashMap<String, Integer>()
            : new HashMap<>(value);
    }

    void setToolResumeInfo(Map<String, AgentToolResumeInfo> value) {
        requireMutable();
        toolResumeInfo = value == null ? new HashMap<String, AgentToolResumeInfo>()
            : new HashMap<>(value);
    }

    int incrementToolExecutionAttempt(String callId) {
        requireMutable();
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolExecutionAttempts == null) toolExecutionAttempts = new HashMap<>();
        int attempt = toolExecutionAttempts.containsKey(callId)
            ? toolExecutionAttempts.get(callId) + 1 : 1;
        toolExecutionAttempts.put(callId, attempt);
        return attempt;
    }

    int getToolExecutionAttempt(String callId) {
        if (toolExecutionAttempts == null) return 0;
        Integer value = toolExecutionAttempts.get(callId);
        return value == null ? 0 : value;
    }

    AgentToolResumeInfo getToolResumeInfo(String callId) {
        if (toolResumeInfo == null) return AgentToolResumeInfo.none();
        AgentToolResumeInfo value = toolResumeInfo.get(callId);
        return value == null ? AgentToolResumeInfo.none() : value;
    }

    void putToolResumeInfo(String callId, AgentToolResumeInfo value) {
        requireMutable();
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolResumeInfo == null) toolResumeInfo = new HashMap<>();
        toolResumeInfo.put(callId, value == null ? AgentToolResumeInfo.none() : value);
    }

    /** 保存一次表单提交；同一 ToolCall 的多轮提交会合并，后提交值覆盖同名字段。 */
    void putToolInputData(String callId, Map<String, ?> value) {
        requireMutable();
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolInputData == null) toolInputData = new HashMap<>();
        Map<String, Object> merged = toolInputData.get(callId) == null
            ? new HashMap<String, Object>() : new HashMap<>(toolInputData.get(callId));
        if (value != null) merged.putAll(value);
        toolInputData.put(callId, merged);
    }

    /**
     * 返回指定工具调用的提交数据副本，避免外部修改持久化状态。
     *
     * @param callId 工具调用 ID
     * @return 不可修改的数据副本；没有记录时返回空 Map
     */
    Map<String, Object> getToolInputData(String callId) {
        if (toolInputData == null) return Collections.emptyMap();
        Map<String, Object> value = toolInputData.get(callId);
        return value == null ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(value));
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

    /**
     * 同时更新持久化异常类型名和消息，避免出现半更新错误状态。
     */
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

    void setMetadata(Map<String, Object> value) {
        requireMutable();
        metadata = value == null ? new HashMap<>() : new HashMap<>(value);
    }

    /**
     * 写入单个运行元数据项。
     *
     * @param key   元数据键
     * @param value 可序列化的元数据值
     */
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

        /**
         * 包装一份专供当前 Builder 修改的可变状态。
         *
         * @param state 不与已发布 Snapshot 共享集合的状态
         */
        private Builder(AgentTurnState state) {
            this.state = state;
        }

        public Builder executionPolicy(AgentExecutionPolicy value) {
            state.setExecutionPolicy(value);
            return this;
        }

        public Builder streaming(boolean value) {
            state.setStreaming(value);
            return this;
        }

        public Builder status(AgentTurnStatus value) {
            state.setStatus(value);
            return this;
        }

        public Builder executionPoint(AgentTurnExecutionPoint value) {
            state.setExecutionPoint(value);
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

        public Builder toolExecutionAttempts(Map<String, Integer> value) {
            state.setToolExecutionAttempts(value);
            return this;
        }

        public Builder toolResumeInfo(Map<String, AgentToolResumeInfo> value) {
            state.setToolResumeInfo(value);
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

        public Builder metadata(Map<String, Object> value) {
            state.setMetadata(value);
            return this;
        }

        /**
         * 冻结当前字段并生成新的不可变深复制。
         *
         * @return 可安全交给 Snapshot 或 Store 的状态
         */
        public AgentTurnState build() {
            return state.immutableCopy();
        }
    }

    /**
     * 深复制按 Tool Call ID 分组的表单数据，并可冻结外层及内层 Map。
     *
     * @param source    原始数据；允许为空
     * @param immutable 是否返回不可修改结构
     * @return 不与来源共享 Map 的两层数据结构
     */
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

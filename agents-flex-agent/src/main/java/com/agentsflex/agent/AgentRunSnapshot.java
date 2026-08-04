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
import java.util.List;
import java.util.Map;

/**
 * AgentRun 在稳定执行边界上的不可变持久化快照。
 *
 * <p>Snapshot 只保存用于重新加载 Agent 的标识和一份不可变 {@link AgentRunState}。ChatModel、Tool、
 * Prompt、Throwable 等进程内对象不会进入快照。恢复时先按 agentId 和 agentVersion 加载完整 Agent，
 * 再由 State 重建 Run。</p>
 */
public final class AgentRunSnapshot implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String agentId;
    private final String agentVersion;
    private final AgentRunState state;

    private AgentRunSnapshot(String agentId, String agentVersion, AgentRunState state) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.state = state.immutableCopy();
    }

    /** 创建包含必要运行标识的 Snapshot 构建器。 */
    public static Builder builder(String runId, String agentId, String agentVersion) {
        return new Builder(runId, agentId, agentVersion);
    }

    static AgentRunSnapshot fromState(String agentId, String agentVersion, AgentRunState state) {
        validate(agentId, agentVersion, state);
        return new AgentRunSnapshot(agentId, agentVersion, state);
    }

    /** @return 与当前快照完全隔离的深拷贝 */
    public AgentRunSnapshot copy() {
        return new AgentRunSnapshot(agentId, agentVersion, state);
    }

    /** 返回内容相同、仅替换 Store 乐观锁版本的新快照。 */
    public AgentRunSnapshot withVersion(long version) {
        AgentRunState copy = state.mutableCopy();
        copy.setVersion(version);
        return new AgentRunSnapshot(agentId, agentVersion, copy);
    }

    /** @return 已填入当前全部字段的兼容构建器 */
    public Builder toBuilder() {
        return new Builder(agentId, agentVersion, state.mutableCopy());
    }

    /**
     * @return Snapshot 持有的不可变运行状态
     */
    public AgentRunState getState() { return state; }

    public String getRunId() { return state.getRunId(); }
    public String getAgentId() { return agentId; }
    public String getAgentVersion() { return agentVersion; }
    public AgentExecutionPolicy getExecutionPolicy() { return state.getExecutionPolicy(); }
    public AgentRunStatus getStatus() { return state.getStatus(); }
    public AgentRunPhase getPhase() { return state.getPhase(); }
    public List<Message> getMessages() { return state.getMessages(); }
    public List<ToolCall> getPendingToolCalls() { return state.getPendingToolCalls(); }
    public AgentSuspension getSuspension() { return state.getSuspension(); }
    public int getIterationCount() { return state.getIterationCount(); }
    public int getStepCount() { return state.getStepCount(); }
    public long getInputTokens() { return state.getInputTokens(); }
    public long getOutputTokens() { return state.getOutputTokens(); }
    public long getTotalTokens() { return state.getTotalTokens(); }
    public int getToolCallCount() { return state.getToolCallCount(); }
    public int getRetryCount() { return state.getRetryCount(); }
    public String getBudgetExceededReason() { return state.getBudgetExceededReason(); }
    public String getLeaseOwner() { return state.getLeaseOwner(); }
    public String getLeaseId() { return state.getLeaseId(); }
    public long getLeaseUntil() { return state.getLeaseUntil(); }
    public Map<String, Boolean> getToolApprovals() { return state.getToolApprovals(); }
    public AgentTaskPlan getTaskPlan() { return state.getTaskPlan(); }
    public boolean isPlanningEnabled() { return state.isPlanningEnabled(); }
    public int getPlanningDepth() { return state.getPlanningDepth(); }
    public boolean isCancellationRequested() { return state.isCancellationRequested(); }
    public boolean isStarted() { return state.isStarted(); }
    public AiMessage getFinalMessage() { return state.getFinalMessage(); }
    public String getErrorType() { return state.getErrorType(); }
    public String getErrorMessage() { return state.getErrorMessage(); }
    public long getCreatedAt() { return state.getCreatedAt(); }
    public long getCompletedAt() { return state.getCompletedAt(); }
    public long getUpdatedAt() { return state.getUpdatedAt(); }
    public long getNextRunAt() { return state.getNextRunAt(); }
    public long getVersion() { return state.getVersion(); }
    public String getParentRunId() { return state.getParentRunId(); }
    public String getRootRunId() { return state.getRootRunId(); }
    public Map<String, Object> getMetadata() { return state.getMetadata(); }

    private static void validate(String agentId, String agentVersion, AgentRunState state) {
        if (!StringUtil.hasText(agentId)) {
            throw new IllegalStateException("agentId must not be blank");
        }
        if (!StringUtil.hasText(agentVersion)) {
            throw new IllegalStateException("agentVersion must not be blank");
        }
        if (state == null || state.getRunId() == null) {
            throw new IllegalStateException("state and runId must not be null");
        }
        if (state.getExecutionPolicy() == null) {
            throw new IllegalStateException("executionPolicy must not be null");
        }
        if (state.getStatus() == null) {
            throw new IllegalStateException("status must not be null");
        }
        if (state.getPhase() == null) {
            throw new IllegalStateException("phase must not be null");
        }
    }

    /**
     * Snapshot 兼容构建器。字段实际写入同一个 AgentRunState，不再在 Builder 内维护镜像。
     */
    public static final class Builder {
        private final String agentId;
        private final String agentVersion;
        private final AgentRunState state;

        private Builder(String runId, String agentId, String agentVersion) {
            if (runId == null || agentId == null || agentVersion == null) {
                throw new IllegalArgumentException(
                    "runId, agentId and agentVersion must not be null");
            }
            this.agentId = agentId;
            this.agentVersion = agentVersion;
            this.state = new AgentRunState(runId, null, 0);
        }

        private Builder(String agentId, String agentVersion, AgentRunState state) {
            this.agentId = agentId;
            this.agentVersion = agentVersion;
            this.state = state;
        }

        public Builder executionPolicy(AgentExecutionPolicy value) { state.setExecutionPolicy(value); return this; }
        public Builder status(AgentRunStatus value) { state.setStatus(value); return this; }
        public Builder phase(AgentRunPhase value) { state.setPhase(value); return this; }
        public Builder messages(List<? extends Message> value) { state.setMessages(value); return this; }
        public Builder pendingToolCalls(List<ToolCall> value) { state.setPendingToolCalls(value); return this; }
        public Builder suspension(AgentSuspension value) { state.setSuspension(value); return this; }
        public Builder iterationCount(int value) { state.setIterationCount(value); return this; }
        public Builder stepCount(int value) { state.setStepCount(value); return this; }
        public Builder inputTokens(long value) { state.setInputTokens(value); return this; }
        public Builder outputTokens(long value) { state.setOutputTokens(value); return this; }
        public Builder totalTokens(long value) { state.setTotalTokens(value); return this; }
        public Builder toolCallCount(int value) { state.setToolCallCount(value); return this; }
        public Builder retryCount(int value) { state.setRetryCount(value); return this; }
        public Builder budgetExceededReason(String value) { state.setBudgetExceededReason(value); return this; }
        public Builder leaseOwner(String value) { state.setLeaseOwner(value); return this; }
        public Builder leaseId(String value) { state.setLeaseId(value); return this; }
        public Builder leaseUntil(long value) { state.setLeaseUntil(value); return this; }
        public Builder toolApprovals(Map<String, Boolean> value) { state.setToolApprovals(value); return this; }
        public Builder taskPlan(AgentTaskPlan value) { state.setTaskPlan(value); return this; }
        public Builder planningEnabled(boolean value) { state.setPlanningEnabled(value); return this; }
        public Builder planningDepth(int value) { state.setPlanningDepth(value); return this; }
        public Builder cancellationRequested(boolean value) { state.setCancellationRequested(value); return this; }
        public Builder started(boolean value) { state.setStarted(value); return this; }
        public Builder finalMessage(AiMessage value) { state.setFinalMessage(value); return this; }
        public Builder error(String type, String message) { state.setError(type, message); return this; }
        public Builder createdAt(long value) { state.setCreatedAt(value); return this; }
        public Builder completedAt(long value) { state.setCompletedAt(value); return this; }
        public Builder updatedAt(long value) { state.setUpdatedAt(value); return this; }
        public Builder nextRunAt(long value) { state.setNextRunAt(value); return this; }
        public Builder version(long value) { state.setVersion(value); return this; }
        public Builder parentRunId(String value) { state.setParentRunId(value); return this; }
        public Builder rootRunId(String value) { state.setRootRunId(value); return this; }
        public Builder metadata(Map<String, Object> value) { state.setMetadata(value); return this; }

        public AgentRunSnapshot build() {
            validate(agentId, agentVersion, state);
            return new AgentRunSnapshot(agentId, agentVersion, state);
        }
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.tool.AgentToolReference;
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
 * <p>Snapshot 不包含 ChatModel、Tool、拦截器或 Java Throwable 等进程内对象。它保存恢复运行模式所需的
 * 数据，并通过 agentId 和 agentVersion 在恢复时重新绑定 {@link Agent}。phase、pendingToolCalls 和
 * pendingToolReferences 用于区分“模型尚未决策”和“模型已经决策但工具尚未执行”，并恢复模型决策时实际绑定的工具。</p>
 *
 * <p>执行模式版本、有效执行策略、模式状态、父子关系、调度时间、租约、审批结果、重试次数和预算用量都会随快照保存，使其他进程可以
 * 从同一个稳定边界继续执行。</p>
 */
public final class AgentRunSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String runId;
    private final String agentId;
    private final String agentVersion;
    private final String executionModeId;
    private final String executionModeVersion;
    private final AgentExecutionPolicy executionPolicy;
    private final AgentRunStatus status;
    private final AgentRunPhase phase;
    private final List<Message> messages;
    private final List<ToolCall> pendingToolCalls;
    private final Map<String, AgentToolReference> pendingToolReferences;
    private final AgentSuspension suspension;
    private final int iterationCount;
    private final int stepCount;
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final int toolCallCount;
    private final int retryCount;
    private final String budgetExceededReason;
    private final String leaseOwner;
    private final long leaseUntil;
    private final Map<String, Boolean> toolApprovals;
    private final boolean cancellationRequested;
    private final boolean started;
    private final AiMessage finalMessage;
    private final String errorType;
    private final String errorMessage;
    private final long createdAt;
    private final long completedAt;
    private final long updatedAt;
    private final long nextRunAt;
    private final long version;
    private final String parentRunId;
    private final String rootRunId;
    private final Map<String, Object> metadata;
    private final Map<String, Object> modeState;

    private AgentRunSnapshot(Builder builder) {
        this.runId = builder.runId;
        this.agentId = builder.agentId;
        this.agentVersion = builder.agentVersion;
        this.executionModeId = builder.executionModeId;
        this.executionModeVersion = builder.executionModeVersion;
        this.executionPolicy = builder.executionPolicy;
        this.status = builder.status;
        this.phase = builder.phase;
        this.messages = Collections.unmodifiableList(AgentMessageUtils.copyMessages(builder.messages));
        this.pendingToolCalls = Collections.unmodifiableList(AgentMessageUtils.copyToolCalls(builder.pendingToolCalls));
        this.pendingToolReferences = builder.pendingToolReferences == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.pendingToolReferences));
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
        this.leaseUntil = builder.leaseUntil;
        this.toolApprovals = builder.toolApprovals == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.toolApprovals));
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
        this.modeState = builder.modeState == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(builder.modeState));
    }

    public static Builder builder(String runId, String agentId, String agentVersion) {
        return new Builder(runId, agentId, agentVersion);
    }

    public AgentRunSnapshot copy() {
        return toBuilder().build();
    }

    public AgentRunSnapshot withVersion(long version) {
        return toBuilder().version(version).build();
    }

    public Builder toBuilder() {
        return new Builder(runId, agentId, agentVersion)
            .executionMode(executionModeId, executionModeVersion)
            .executionPolicy(executionPolicy)
            .status(status)
            .phase(phase)
            .messages(messages)
            .pendingToolCalls(pendingToolCalls)
            .pendingToolReferences(pendingToolReferences)
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
            .leaseUntil(leaseUntil)
            .toolApprovals(toolApprovals)
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
            .metadata(metadata)
            .modeState(modeState);
    }

    public String getRunId() {
        return runId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentVersion() { return agentVersion; }
    public String getExecutionModeId() { return executionModeId; }
    public String getExecutionModeVersion() { return executionModeVersion; }
    public AgentExecutionPolicy getExecutionPolicy() { return executionPolicy; }

    public AgentRunStatus getStatus() {
        return status;
    }

    public AgentRunPhase getPhase() {
        return phase;
    }

    public List<Message> getMessages() {
        return Collections.unmodifiableList(AgentMessageUtils.copyMessages(messages));
    }

    public List<ToolCall> getPendingToolCalls() {
        return Collections.unmodifiableList(AgentMessageUtils.copyToolCalls(pendingToolCalls));
    }

    /** @return ToolCall ID 到持久化工具引用的只读映射 */
    public Map<String, AgentToolReference> getPendingToolReferences() {
        return pendingToolReferences;
    }

    public AgentSuspension getSuspension() {
        return suspension == null ? null : suspension.copy();
    }

    public int getIterationCount() {
        return iterationCount;
    }

    public int getStepCount() { return stepCount; }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public int getToolCallCount() { return toolCallCount; }
    public int getRetryCount() { return retryCount; }
    public String getBudgetExceededReason() { return budgetExceededReason; }
    public String getLeaseOwner() { return leaseOwner; }
    public long getLeaseUntil() { return leaseUntil; }
    public Map<String, Boolean> getToolApprovals() { return toolApprovals; }

    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    public boolean isStarted() {
        return started;
    }

    public AiMessage getFinalMessage() {
        return finalMessage == null ? null : finalMessage.copy();
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public long getNextRunAt() {
        return nextRunAt;
    }

    public long getVersion() {
        return version;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Map<String, Object> getModeState() { return modeState; }

    /** Snapshot 构建器，供 Store、Codec 和数据库映射设置字段。 */
    public static final class Builder {

        private final String runId;
        private final String agentId;
        private final String agentVersion;
        private String executionModeId;
        private String executionModeVersion;
        private AgentExecutionPolicy executionPolicy;
        private AgentRunStatus status = AgentRunStatus.READY;
        private AgentRunPhase phase = AgentRunPhase.MODEL;
        private List<Message> messages = Collections.emptyList();
        private List<ToolCall> pendingToolCalls = Collections.emptyList();
        private Map<String, AgentToolReference> pendingToolReferences;
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
        private long leaseUntil;
        private Map<String, Boolean> toolApprovals;
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
        private Map<String, Object> modeState;

        private Builder(String runId, String agentId, String agentVersion) {
            if (runId == null || agentId == null || agentVersion == null) {
                throw new IllegalArgumentException(
                    "runId, agentId and agentVersion must not be null");
            }
            this.runId = runId;
            this.agentId = agentId;
            this.agentVersion = agentVersion;
        }

        public Builder status(AgentRunStatus status) { this.status = status; return this; }
        public Builder executionMode(String id, String version) { this.executionModeId = id; this.executionModeVersion = version; return this; }
        public Builder executionPolicy(AgentExecutionPolicy value) { this.executionPolicy = value; return this; }
        public Builder phase(AgentRunPhase phase) { this.phase = phase; return this; }
        public Builder messages(List<? extends Message> messages) { this.messages = AgentMessageUtils.copyMessages(messages); return this; }
        public Builder pendingToolCalls(List<ToolCall> calls) { this.pendingToolCalls = AgentMessageUtils.copyToolCalls(calls); return this; }
        public Builder pendingToolReferences(Map<String, AgentToolReference> value) { this.pendingToolReferences = value; return this; }
        public Builder suspension(AgentSuspension suspension) { this.suspension = suspension; return this; }
        public Builder iterationCount(int value) { this.iterationCount = value; return this; }
        public Builder stepCount(int value) { this.stepCount = value; return this; }
        public Builder inputTokens(long value) { this.inputTokens = value; return this; }
        public Builder outputTokens(long value) { this.outputTokens = value; return this; }
        public Builder totalTokens(long value) { this.totalTokens = value; return this; }
        public Builder toolCallCount(int value) { this.toolCallCount = value; return this; }
        public Builder retryCount(int value) { this.retryCount = value; return this; }
        public Builder budgetExceededReason(String value) { this.budgetExceededReason = value; return this; }
        public Builder leaseOwner(String value) { this.leaseOwner = value; return this; }
        public Builder leaseUntil(long value) { this.leaseUntil = value; return this; }
        public Builder toolApprovals(Map<String, Boolean> value) { this.toolApprovals = value; return this; }
        public Builder cancellationRequested(boolean value) { this.cancellationRequested = value; return this; }
        public Builder started(boolean value) { this.started = value; return this; }
        public Builder finalMessage(AiMessage value) { this.finalMessage = value; return this; }
        public Builder error(String type, String message) { this.errorType = type; this.errorMessage = message; return this; }
        public Builder createdAt(long value) { this.createdAt = value; return this; }
        public Builder completedAt(long value) { this.completedAt = value; return this; }
        public Builder updatedAt(long value) { this.updatedAt = value; return this; }
        public Builder nextRunAt(long value) { this.nextRunAt = value; return this; }
        public Builder version(long value) { this.version = value; return this; }
        public Builder parentRunId(String value) { this.parentRunId = value; return this; }
        public Builder rootRunId(String value) { this.rootRunId = value; return this; }
        public Builder metadata(Map<String, Object> value) { this.metadata = value; return this; }
        public Builder modeState(Map<String, Object> value) { this.modeState = value; return this; }

        public AgentRunSnapshot build() {
            if (!StringUtil.hasText(agentVersion)) {
                throw new IllegalStateException("agentVersion must not be blank");
            }
            if (!StringUtil.hasText(executionModeId)
                || !StringUtil.hasText(executionModeVersion)) {
                throw new IllegalStateException(
                    "executionModeId and executionModeVersion must not be blank");
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

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
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个 Agent 的单次可变运行状态。
 *
 * <p>{@link Agent} 可以被多次复用，但每次任务都应该创建独立的 AgentRun。该对象保存：</p>
 * <ul>
 *     <li>本次运行唯一 ID；</li>
 *     <li>包含 System、User、AI ToolCall 和 ToolMessage 的完整对话上下文；</li>
 *     <li>生命周期状态、模型迭代次数、最终消息和失败原因；</li>
 *     <li>业务侧附加的运行元数据以及协作式取消标记。</li>
 * </ul>
 *
 * <p>状态转换方法仅对同包内 Runner 可见，避免业务代码直接把运行标记为完成或失败。
 * AgentRun 不是并发执行容器，同一个 Run 不应由多个 Runner 线程同时推进。</p>
 */
public final class AgentRun {

    /**
     * 本次运行的唯一标识。
     */
    private final String id;
    /**
     * 本次运行使用的不可变 Agent 定义。
     */
    private final Agent agent;
    /** Run 创建时冻结的有效执行策略。 */
    private final AgentExecutionPolicy executionPolicy;
    /**
     * 保存模型交互历史和可用工具的 Prompt。
     */
    private final MemoryPrompt prompt;
    /**
     * 运行创建时间，使用毫秒时间戳。
     */
    private final long createdAt;
    /**
     * 供调用方附加 Trace ID、租户 ID 等业务数据。
     */
    private final Map<String, Object> metadata = new HashMap<>();
    /** 运行模式用于保存可持久化自定义状态的命名空间。 */
    private final Map<String, Object> modeState = new HashMap<>();
    /** 当前进程附加的调用上下文，不参与 Checkpoint 持久化。 */
    private transient AgentInvocationContext invocationContext = AgentInvocationContext.empty();

    /** 当前固定执行阶段。 */
    private AgentRunPhase phase;
    /** 模型已经返回、但尚未全部执行完成的工具调用。 */
    private List<ToolCall> pendingToolCalls = new ArrayList<>();
    /** 待执行 ToolCall 的持久化工具引用，key 为 ToolCall ID。 */
    private final Map<String, AgentToolReference> pendingToolReferences = new HashMap<>();
    /** Run 进入阻塞状态时保存的等待原因和恢复目标。 */
    private AgentSuspension suspension;
    /** Store 中最新 Checkpoint 的乐观锁版本，首次保存前为 -1。 */
    private long version = -1;
    /** 直接父 Run ID；根任务没有父 Run。 */
    private String parentRunId;
    /** 整个父子任务树的根 Run ID。 */
    private String rootRunId;
    /** 重试或延迟任务的下一次可执行时间。 */
    private long nextRunAt;
    /** 已累计的模型输入 Token。 */
    private long inputTokens;
    /** 已累计的模型输出 Token。 */
    private long outputTokens;
    /** 已累计的模型总 Token。 */
    private long totalTokens;
    /** 已经实际开始执行的工具调用次数。 */
    private int toolCallCount;
    /** 已经安排的自动重试次数。 */
    private int retryCount;
    /** 超出预算时保存的限制名称。 */
    private String budgetExceededReason;
    /** 当前持有运行租约的 Worker ID。 */
    private String leaseOwner;
    /** 当前租约到期时间。 */
    private long leaseUntil;
    /** 已审批工具调用的决定，key 为 ToolCall ID。 */
    private final Map<String, Boolean> toolApprovals = new HashMap<>();

    /**
     * 协作式取消标记，使用 volatile 保证执行线程能够看到外部取消请求。
     */
    private volatile boolean cancellationRequested;
    /**
     * 标记 onRunStart 是否已经发送，确保生命周期开始事件只触发一次。
     */
    private boolean started;
    /**
     * 当前生命周期状态。
     */
    private AgentRunStatus status = AgentRunStatus.READY;
    /**
     * 已经发起的模型调用次数，不是工具调用次数。
     */
    private int iterationCount;
    /** AgentExecutionMode 已经推进的 step 次数。 */
    private int stepCount;
    /**
     * 正常结束时模型返回的最终消息。
     */
    private AiMessage finalMessage;
    /**
     * 失败结束时记录的原始异常。
     */
    private Throwable error;
    /**
     * 进入任一终止状态的时间；未结束时为 0。
     */
    private long completedAt;

    private AgentRun(String id, Agent agent, MemoryPrompt prompt, long createdAt,
                     AgentExecutionPolicy executionPolicy) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null");
        }
        this.id = id;
        this.agent = agent;
        this.executionPolicy = executionPolicy == null
            ? agent.getExecutionPolicy() : executionPolicy;
        this.prompt = prompt;
        this.createdAt = createdAt;
        this.rootRunId = id;
        this.phase = AgentRunPhase.MODEL;
        preparePrompt();
    }

    /**
     * 使用一条新的用户输入创建运行。
     *
     * @param agent     要执行的 Agent 定义
     * @param userInput 初始用户输入
     * @return 状态为 {@link AgentRunStatus#READY} 的新运行
     */
    public static AgentRun start(Agent agent, String userInput) {
        return start(agent, userInput, AgentRunOptions.defaults());
    }

    /** 使用单次运行选项创建 Run。 */
    public static AgentRun start(Agent agent, String userInput, AgentRunOptions options) {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addUserMessage(userInput);
        String runId = UUID.randomUUID().toString();
        AgentRunOptions actual = options == null ? AgentRunOptions.defaults() : options;
        AgentRun run = new AgentRun(runId, agent, prompt, System.currentTimeMillis(),
            actual.getExecutionPolicy());
        run.metadata.putAll(actual.getMetadata());
        run.invocationContext = actual.getInvocationContext();
        return run;
    }

    /**
     * 使用已有 MemoryPrompt 创建运行，适合由应用恢复已经保存的消息历史。
     *
     * <p>该方法会重新应用 Agent 的系统指令和工具列表，但会保留 Prompt 中已有的消息。</p>
     */
    public static AgentRun fromPrompt(Agent agent, MemoryPrompt prompt) {
        String runId = UUID.randomUUID().toString();
        return new AgentRun(runId, agent, prompt, System.currentTimeMillis(),
            agent.getExecutionPolicy());
    }

    /** 创建一个继承父任务根 ID 的子运行。 */
    static AgentRun startChild(Agent agent, String userInput, AgentRun parent) {
        AgentRun child = start(agent, userInput);
        child.parentRunId = parent.getId();
        child.rootRunId = parent.getRootRunId();
        // 同一进程内创建子 Run 时继承当前调用身份；从 Checkpoint 恢复后仍需重新附加。
        child.invocationContext = parent.getInvocationContext();
        return child;
    }

    /**
     * 使用持久化 Snapshot 恢复 AgentRun。
     *
     * <p>Snapshot 保存 agentId 和 agentVersion，因此调用方必须先通过 Registry 解析出匹配的 Agent 定义。</p>
     */
    public static AgentRun fromSnapshot(Agent agent, AgentRunSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (!agent.getId().equals(snapshot.getAgentId())) {
            throw new IllegalArgumentException("Agent id does not match snapshot: " + snapshot.getAgentId());
        }

        MemoryPrompt prompt = new MemoryPrompt();
        for (Message message : snapshot.getMessages()) {
            prompt.addMessage(message);
        }
        if (snapshot.getAgentVersion() != null
            && !agent.getVersion().equals(snapshot.getAgentVersion())) {
            throw new IllegalArgumentException("Agent version does not match snapshot: "
                + snapshot.getAgentVersion());
        }
        if (snapshot.getExecutionModeId() != null
            && (!snapshot.getExecutionModeId().equals(agent.getExecutionMode().getId())
                || !java.util.Objects.equals(snapshot.getExecutionModeVersion(),
                    agent.getExecutionMode().getVersion()))) {
            throw new IllegalArgumentException("Agent execution mode does not match snapshot: "
                + snapshot.getExecutionModeId() + ":" + snapshot.getExecutionModeVersion());
        }
        AgentRun run = new AgentRun(snapshot.getRunId(), agent, prompt, snapshot.getCreatedAt(),
            snapshot.getExecutionPolicy());
        run.status = snapshot.getStatus();
        run.phase = snapshot.getPhase();
        run.pendingToolCalls = AgentMessageUtils.copyToolCalls(snapshot.getPendingToolCalls());
        run.pendingToolReferences.putAll(snapshot.getPendingToolReferences());
        run.suspension = snapshot.getSuspension();
        run.iterationCount = snapshot.getIterationCount();
        run.stepCount = snapshot.getStepCount();
        run.inputTokens = snapshot.getInputTokens();
        run.outputTokens = snapshot.getOutputTokens();
        run.totalTokens = snapshot.getTotalTokens();
        run.toolCallCount = snapshot.getToolCallCount();
        run.retryCount = snapshot.getRetryCount();
        run.budgetExceededReason = snapshot.getBudgetExceededReason();
        run.leaseOwner = snapshot.getLeaseOwner();
        run.leaseUntil = snapshot.getLeaseUntil();
        run.toolApprovals.putAll(snapshot.getToolApprovals());
        run.cancellationRequested = snapshot.isCancellationRequested();
        run.started = snapshot.isStarted();
        run.finalMessage = snapshot.getFinalMessage();
        if (snapshot.getErrorMessage() != null) {
            run.error = new RestoredAgentRunException(snapshot.getErrorType(), snapshot.getErrorMessage());
        }
        run.completedAt = snapshot.getCompletedAt();
        run.nextRunAt = snapshot.getNextRunAt();
        run.version = snapshot.getVersion();
        run.parentRunId = snapshot.getParentRunId();
        run.rootRunId = StringUtil.hasText(snapshot.getRootRunId())
            ? snapshot.getRootRunId() : snapshot.getRunId();
        run.metadata.putAll(snapshot.getMetadata());
        run.modeState.putAll(snapshot.getModeState());
        return run;
    }

    /**
     * 将 Agent 的静态定义装配到本次运行使用的 Prompt。
     */
    private void preparePrompt() {
        if (StringUtil.hasText(agent.getInstructions())) {
            prompt.setSystemMessage(agent.getInstructions());
        }
        prompt.setTools(agent.getTools());
        agent.getContextPolicy().configure(prompt);
    }

    /**
     * 请求取消本次运行。
     *
     * <p>取消是协作式的：该方法不会中断正在执行的模型 HTTP 请求或 Tool Java 方法，Runner 会在
     * 下一个安全检查点停止继续调用模型或执行后续工具。</p>
     */
    void requestCancellation() {
        if (!status.isTerminal()) {
            this.cancellationRequested = true;
        }
    }

    /**
     * @return 是否已经收到取消请求
     */
    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    /**
     * @return 本次运行的唯一 ID
     */
    public String getId() {
        return id;
    }

    /**
     * @return 本次运行使用的 Agent 定义
     */
    public Agent getAgent() {
        return agent;
    }

    /** @return 本次运行创建时冻结的有效执行策略 */
    public AgentExecutionPolicy getExecutionPolicy() { return executionPolicy; }

    /** @return 当前进程为 Run 附加的调用上下文 */
    public AgentInvocationContext getInvocationContext() {
        return invocationContext == null ? AgentInvocationContext.empty() : invocationContext;
    }

    /** 为新建或恢复的 Run 附加本次调用上下文。 */
    public AgentRun attachInvocationContext(AgentInvocationContext context) {
        this.invocationContext = context == null ? AgentInvocationContext.empty() : context;
        return this;
    }

    /**
     * @return 本次运行的可变 Prompt，其中包含完整消息历史
     */
    public MemoryPrompt getPrompt() {
        return prompt;
    }

    /**
     * @return 当前生命周期状态
     */
    public AgentRunStatus getStatus() {
        return status;
    }

    /** @return 当前模型或工具执行阶段 */
    public AgentRunPhase getPhase() {
        return phase;
    }

    /** @return 尚待执行的工具调用深拷贝列表 */
    public List<ToolCall> getPendingToolCalls() {
        return Collections.unmodifiableList(AgentMessageUtils.copyToolCalls(pendingToolCalls));
    }

    /** @return ToolCall ID 到持久化工具引用的只读映射 */
    public Map<String, AgentToolReference> getPendingToolReferences() {
        return Collections.unmodifiableMap(new HashMap<>(pendingToolReferences));
    }

    /** @return 当前阻塞原因；非阻塞状态时为 null */
    public AgentSuspension getSuspension() {
        return suspension == null ? null : suspension.copy();
    }

    /**
     * @return 已完成或已发起的模型调用次数
     */
    public int getIterationCount() {
        return iterationCount;
    }

    public int getStepCount() { return stepCount; }

    /**
     * @return 正常完成时的最终 AiMessage；未正常完成时为 {@code null}
     */
    public AiMessage getFinalMessage() {
        return finalMessage;
    }

    /**
     * 便捷获取最终消息的文本内容。
     *
     * @return 最终文本；尚未完成或没有最终消息时为 {@code null}
     */
    public String getFinalOutput() {
        return finalMessage == null ? null : finalMessage.getContent();
    }

    /**
     * @return 运行失败时的异常；非 FAILED 状态通常为 {@code null}
     */
    public Throwable getError() {
        return error;
    }

    /**
     * @return 运行创建时间的毫秒时间戳
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * @return 运行结束时间的毫秒时间戳；尚未结束时为 0
     */
    public long getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public String getRootRunId() {
        return rootRunId;
    }

    public long getNextRunAt() {
        return nextRunAt;
    }

    public int getToolCallCount() { return toolCallCount; }

    public int getRetryCount() { return retryCount; }

    public String getBudgetExceededReason() { return budgetExceededReason; }

    public String getLeaseOwner() { return leaseOwner; }

    public long getLeaseUntil() { return leaseUntil; }

    /**
     * 返回只读元数据视图。
     *
     * @return 不允许直接修改的元数据 Map
     */
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * 添加或覆盖一项业务运行元数据。
     */
    public void putMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    /** 返回运行模式的只读持久化状态。 */
    public Map<String, Object> getModeState() {
        return Collections.unmodifiableMap(modeState);
    }

    /** 由自定义运行模式保存一个可序列化状态值。 */
    public void putModeState(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("mode state key must not be null");
        }
        modeState.put(key, value);
    }

    /**
     * 生成与当前可变状态隔离的 Checkpoint Snapshot。
     */
    public AgentRunSnapshot toSnapshot() {
        // getMessages() 可能受附加消息数量限制；Checkpoint 必须保存 Memory 中的完整历史。
        List<Message> messages = prompt.getMemory().getMessages(Integer.MAX_VALUE);
        SystemMessage systemMessage = prompt.getSystemMessage();
        if (systemMessage != null && (messages.isEmpty() || !(messages.get(0) instanceof SystemMessage))) {
            messages.add(0, systemMessage);
        }
        String errorType = error == null ? null : error.getClass().getName();
        String errorMessage = error == null ? null : error.getMessage();
        return AgentRunSnapshot.builder(id, agent.getId(), agent.getVersion())
            .executionMode(agent.getExecutionMode().getId(), agent.getExecutionMode().getVersion())
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
            .updatedAt(System.currentTimeMillis())
            .nextRunAt(nextRunAt)
            .version(version)
            .parentRunId(parentRunId)
            .rootRunId(rootRunId)
            .metadata(metadata)
            .modeState(modeState)
            .build();
    }

    /**
     * 标记第一次开始执行，并返回是否需要发送 onRunStart 事件。
     */
    boolean markStarted() {
        if (started) {
            return false;
        }
        started = true;
        status = AgentRunStatus.RUNNING;
        return true;
    }

    /**
     * 增加一次模型迭代计数。
     */
    void incrementIteration() {
        iterationCount++;
    }

    void incrementStep() { stepCount++; }

    /** 累计一次模型响应报告的 Token Usage。 */
    void addUsage(AiMessage message) {
        if (message == null) {
            return;
        }
        Integer promptTokens = message.getPromptTokens() != null
            ? message.getPromptTokens() : message.getLocalPromptTokens();
        Integer completionTokens = message.getCompletionTokens() != null
            ? message.getCompletionTokens() : message.getLocalCompletionTokens();
        Integer messageTotalTokens = message.getTotalTokens() != null
            ? message.getTotalTokens() : message.getLocalTotalTokens();
        if (promptTokens != null) {
            inputTokens += promptTokens;
        }
        if (completionTokens != null) {
            outputTokens += completionTokens;
        }
        if (messageTotalTokens != null) {
            totalTokens += messageTotalTokens;
        } else {
            totalTokens += (promptTokens == null ? 0 : promptTokens)
                + (completionTokens == null ? 0 : completionTokens);
        }
    }

    /** 保存模型已经决定、但尚未执行的 ToolCall。 */
    void setPendingToolCalls(List<ToolCall> toolCalls) {
        this.pendingToolCalls = AgentMessageUtils.copyToolCalls(toolCalls);
        this.pendingToolReferences.clear();
    }

    /** 保存一个待执行 ToolCall 对应的持久化工具引用。 */
    void putPendingToolReference(String callId, AgentToolReference reference) {
        if (callId != null && reference != null) {
            pendingToolReferences.put(callId, reference);
        }
    }

    /** 返回待执行 ToolCall 已冻结的工具引用。 */
    AgentToolReference getPendingToolReference(String callId) {
        return callId == null ? null : pendingToolReferences.get(callId);
    }

    /** 清除已经完成处理的 ToolCall。 */
    void clearPendingToolCalls() {
        this.pendingToolCalls.clear();
        this.pendingToolReferences.clear();
    }

    /** 移除已经写入结果的首个待执行工具调用。 */
    void removeFirstPendingToolCall() {
        if (!pendingToolCalls.isEmpty()) {
            ToolCall removed = pendingToolCalls.remove(0);
            if (removed != null) {
                String callId = StringUtil.hasText(removed.getId())
                    ? removed.getId() : removed.getName();
                pendingToolReferences.remove(callId);
            }
        }
    }

    /** 记录一次已经开始的工具执行。 */
    void incrementToolCallCount() { toolCallCount++; }

    /** 保存工具审批结果。 */
    void approveTool(String callId, boolean approved) { toolApprovals.put(callId, approved); }

    /** 返回工具审批结果；尚未审批时返回 null。 */
    Boolean getToolApproval(String callId) { return toolApprovals.get(callId); }

    /** 安排一次持久化重试。 */
    void scheduleRetry(Throwable error, AgentRunPhase resumePhase, long runAt) {
        retryCount++;
        this.error = error;
        this.nextRunAt = runAt;
        suspend(AgentRunStatus.RETRY_SCHEDULED,
            AgentSuspension.retry(error == null ? null : error.getMessage(), resumePhase, runAt));
    }

    /** 清除上一次可恢复异常。 */
    void clearRetryError() { this.error = null; }

    /** 进入指定的运行中阶段。 */
    void moveTo(AgentRunPhase phase) {
        this.status = AgentRunStatus.RUNNING;
        this.phase = phase;
        this.suspension = null;
    }

    /** 进入阻塞状态并保存恢复信息。 */
    void suspend(AgentRunStatus status, AgentSuspension suspension) {
        this.status = status;
        this.phase = suspension.getResumePhase();
        this.suspension = suspension;
    }

    /** 清除阻塞信息并回到指定执行阶段。 */
    void resumeAt(AgentRunPhase phase) {
        this.suspension = null;
        this.status = AgentRunStatus.RUNNING;
        this.phase = phase == null ? AgentRunPhase.MODEL : phase;
        this.nextRunAt = 0;
    }

    /** 进入终止状态并记录结束时间。 */
    private void finish(AgentRunStatus status) {
        this.status = status;
        this.phase = AgentRunPhase.FINISHED;
        this.suspension = null;
        this.completedAt = System.currentTimeMillis();
    }

    /** Runner 成功保存 Checkpoint 后更新本地版本。 */
    void updateVersion(long version) {
        this.version = version;
    }

    /**
     * 工具执行完成后保持运行态，等待下一个模型回合。
     */
    void markRunning() {
        moveTo(AgentRunPhase.MODEL);
    }

    /**
     * 标记正常完成并保存最终模型消息。
     */
    void markCompleted(AiMessage finalMessage) {
        this.finalMessage = finalMessage;
        finish(AgentRunStatus.COMPLETED);
    }

    /**
     * 标记失败并保存原始异常。
     */
    void markFailed(Throwable error) {
        this.error = error;
        finish(AgentRunStatus.FAILED);
    }

    /**
     * 标记取消完成。
     */
    void markCancelled() {
        finish(AgentRunStatus.CANCELLED);
    }

    /**
     * 标记因达到最大模型迭代次数而结束。
     */
    void markMaxIterationsReached() {
        finish(AgentRunStatus.MAX_ITERATIONS_REACHED);
    }

    /** 标记自定义或默认执行模式达到总 step 上限。 */
    void markMaxStepsReached() { finish(AgentRunStatus.MAX_STEPS_REACHED); }

    /** 标记预算耗尽并保存触发限制。 */
    void markBudgetExceeded(String reason) {
        this.budgetExceededReason = reason;
        finish(AgentRunStatus.BUDGET_EXCEEDED);
    }

    /** 更新当前租约信息。 */
    void updateLease(String owner, long until) {
        this.leaseOwner = owner;
        this.leaseUntil = until;
    }

    /** 恢复失败状态时使用的轻量异常，不尝试重建原始异常类型和堆栈。 */
    private static final class RestoredAgentRunException extends RuntimeException {

        private RestoredAgentRunException(String errorType, String message) {
            super((errorType == null ? "" : errorType + ": ") + message);
        }
    }
}

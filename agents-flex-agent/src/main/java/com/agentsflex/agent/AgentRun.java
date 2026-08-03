/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.task.AgentPlanningTool;
import com.agentsflex.agent.task.AgentTaskPlan;
import com.agentsflex.agent.task.AgentTaskProgress;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.UserMessage;
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
    /**
     * Run 创建时冻结的有效执行策略。
     */
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
    /**
     * 当前进程附加的调用上下文，不参与 Checkpoint 持久化。
     */
    private transient AgentInvocationContext invocationContext = AgentInvocationContext.empty();
    /**
     * 创建本 Run 的持续对话上下文，不参与 Checkpoint 持久化。
     */
    private transient AgentConversation conversation;

    /**
     * 当前固定执行阶段。
     */
    private AgentRunPhase phase;
    /**
     * 模型已经返回、但尚未全部执行完成的工具调用。
     */
    private List<ToolCall> pendingToolCalls = new ArrayList<>();
    /**
     * Run 进入阻塞状态时保存的等待原因和恢复目标。
     */
    private AgentSuspension suspension;
    /**
     * Store 中最新 Checkpoint 的乐观锁版本，首次保存前为 -1。
     */
    private long version = -1;
    /**
     * 直接父 Run ID；根任务没有父 Run。
     */
    private String parentRunId;
    /**
     * 整个父子任务树的根 Run ID。
     */
    private String rootRunId;
    /**
     * 重试或延迟任务的下一次可执行时间。
     */
    private long nextRunAt;
    /**
     * 已累计的模型输入 Token。
     */
    private long inputTokens;
    /**
     * 已累计的模型输出 Token。
     */
    private long outputTokens;
    /**
     * 已累计的模型总 Token。
     */
    private long totalTokens;
    /**
     * 已经实际开始执行的工具调用次数。
     */
    private int toolCallCount;
    /**
     * 已经安排的自动重试次数。
     */
    private int retryCount;
    /**
     * 超出预算时保存的限制名称。
     */
    private String budgetExceededReason;
    /**
     * 当前持有运行租约的 Worker ID。
     */
    private volatile String leaseOwner;
    /**
     * 每次领取时生成的唯一租约令牌，用于区分同名 Worker 的不同进程实例。
     */
    private volatile String leaseId;
    /**
     * 当前租约到期时间。
     */
    private volatile long leaseUntil;
    /**
     * 已审批工具调用的决定，key 为 ToolCall ID。
     */
    private final Map<String, Boolean> toolApprovals = new HashMap<>();
    /**
     * 模型在本 Run 中创建的任务计划；未规划时为 null。
     */
    private AgentTaskPlan taskPlan;
    /**
     * 当前 Run 是否向模型暴露内置规划工具。
     */
    private boolean planningEnabled;
    /**
     * 当前进程是否已经通过 AgentLoader 解析并装配规划委派目标。
     */
    private transient boolean planningToolsPrepared;
    /**
     * 当前 Run 在父子任务树中的规划深度，根 Run 为 0。
     */
    private int planningDepth;

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
    /**
     * Runner 已经推进的 step 次数。
     */
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
        this.planningEnabled = agent.getPlanningPolicy().isEnabled();
        preparePrompt();
    }

    /**
     * 使用一条新的用户输入创建运行。
     *
     * @param agent     要执行的 Agent 定义
     * @param userInput 初始用户输入
     * @return 状态为 {@link AgentRunStatus#READY} 的新运行
     */
    static AgentRun start(Agent agent, String userInput) {
        return start(agent, new UserMessage(userInput), AgentRunOptions.defaults());
    }

    /**
     * 使用单次运行选项创建 Run。
     */
    static AgentRun start(Agent agent, String userInput, AgentRunOptions options) {
        return start(agent, new UserMessage(userInput), options);
    }

    /**
     * 使用一条结构化用户消息创建运行。
     *
     * <p>UserMessage 可以同时携带文本、图片、音频、视频、文件和消息元数据。Run 会复制输入消息，
     * 调用方在创建后继续修改原消息不会影响本次运行及其 Checkpoint。</p>
     */
    static AgentRun start(Agent agent, UserMessage userMessage) {
        return start(agent, userMessage, AgentRunOptions.defaults());
    }

    /**
     * 使用结构化用户消息和单次运行选项创建 Run。
     */
    static AgentRun start(Agent agent, UserMessage userMessage, AgentRunOptions options) {
        return start(agent, Collections.<Message>emptyList(), userMessage, options);
    }

    /**
     * 使用指定 ChatMemory 中的现有历史和本轮结构化消息创建 Run。
     *
     * <p>运行期间产生的用户、模型和工具消息会直接写入同一个 Memory，供下一轮对话继续使用。</p>
     */
    static AgentRun start(Agent agent, ChatMemory memory, UserMessage userMessage,
                          AgentRunOptions options) {
        if (memory == null) {
            throw new IllegalArgumentException("memory must not be null");
        }
        if (userMessage == null) {
            throw new IllegalArgumentException("userMessage must not be null");
        }
        MemoryPrompt prompt = new MemoryPrompt(memory);
        prompt.addMessage(userMessage.copy());
        return create(agent, prompt, options);
    }

    /**
     * 使用已有会话历史和本轮用户消息创建独立运行。
     *
     * <p>会话历史由上层应用加载，可以来自任意业务表或消息存储。每一轮都会复制历史并创建新的
     * AgentRun，因此已完成 Run 的状态、预算和审计记录不会被下一轮对话修改。历史中的 SystemMessage
     * 不会被复制，系统指令始终以当前 Agent 定义为准。</p>
     */
    static AgentRun start(Agent agent, List<? extends Message> conversationHistory,
                          UserMessage userMessage) {
        return start(agent, conversationHistory, userMessage, AgentRunOptions.defaults());
    }

    /**
     * 使用会话历史、结构化用户消息和单次运行选项创建 Run。
     */
    static AgentRun start(Agent agent, List<? extends Message> conversationHistory,
                          UserMessage userMessage, AgentRunOptions options) {
        if (userMessage == null) {
            throw new IllegalArgumentException("userMessage must not be null");
        }
        MemoryPrompt prompt = new MemoryPrompt();
        if (conversationHistory != null) {
            for (Message message : conversationHistory) {
                if (message == null) {
                    throw new IllegalArgumentException("conversationHistory must not contain null messages");
                }
                if (!(message instanceof SystemMessage)) {
                    prompt.addMessage(AgentMessageUtils.copyMessage(message));
                }
            }
        }
        prompt.addMessage(userMessage.copy());
        return create(agent, prompt, options);
    }

    /**
     * 统一应用运行选项并创建新的 READY Run。
     */
    private static AgentRun create(Agent agent, MemoryPrompt prompt, AgentRunOptions options) {
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
    static AgentRun fromPrompt(Agent agent, MemoryPrompt prompt) {
        String runId = UUID.randomUUID().toString();
        return new AgentRun(runId, agent, prompt, System.currentTimeMillis(),
            agent.getExecutionPolicy());
    }

    /**
     * 创建一个继承父任务根 ID 的子运行。
     */
    static AgentRun startChild(Agent agent, String userInput, AgentRun parent) {
        AgentRun child = start(agent, userInput);
        child.parentRunId = parent.getId();
        child.rootRunId = parent.getRootRunId();
        // 同一进程内创建子 Run 时继承当前调用身份；从 Checkpoint 恢复后仍需重新附加。
        child.invocationContext = parent.getInvocationContext();
        child.planningDepth = parent.planningDepth + 1;
        child.planningEnabled = agent.getPlanningPolicy().isEnabled()
            && parent.getAgent().getPlanningPolicy().isChildPlanningAllowed()
            && child.planningDepth < parent.getAgent().getPlanningPolicy().getMaxDepth();
        child.prepareBaseTools();
        return child;
    }

    /**
     * 使用持久化 Snapshot 恢复 AgentRun。
     *
     * <p>Snapshot 保存 agentId 和 agentVersion，因此调用方必须先通过 AgentLoader 加载匹配版本的 Agent。</p>
     */
    static AgentRun fromSnapshot(Agent agent, AgentRunSnapshot snapshot) {
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
        AgentRun run = new AgentRun(snapshot.getRunId(), agent, prompt, snapshot.getCreatedAt(),
            snapshot.getExecutionPolicy());
        run.status = snapshot.getStatus();
        run.phase = snapshot.getPhase();
        run.pendingToolCalls = AgentMessageUtils.copyToolCalls(snapshot.getPendingToolCalls());
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
        run.leaseId = snapshot.getLeaseId();
        run.leaseUntil = snapshot.getLeaseUntil();
        run.toolApprovals.putAll(snapshot.getToolApprovals());
        run.taskPlan = snapshot.getTaskPlan();
        run.planningEnabled = snapshot.isPlanningEnabled();
        run.planningDepth = snapshot.getPlanningDepth();
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
        run.prepareBaseTools();
        return run;
    }

    /**
     * 将 Agent 的静态定义装配到本次运行使用的 Prompt。
     */
    private void preparePrompt() {
        if (StringUtil.hasText(agent.getInstructions())) {
            prompt.setSystemMessage(agent.getInstructions());
        }
        prepareBaseTools();
        agent.getContextPolicy().configure(prompt);
    }

    /**
     * 装配不依赖运行时解析的业务工具，规划工具稍后由 Runner 统一补充。
     */
    private void prepareBaseTools() {
        prompt.setTools(new ArrayList<>(agent.getTools()));
        planningToolsPrepared = !planningEnabled;
    }

    /**
     * 使用 AgentLoader 已解析出的完整 Agent 装配模型可见的规划工具。
     */
    void preparePlanningTools(List<Agent> delegates) {
        List<com.agentsflex.core.model.chat.tool.Tool> tools = new ArrayList<>(agent.getTools());
        if (planningEnabled) {
            tools.addAll(AgentPlanningTool.createTools(
                agent, delegates, agent.getPlanningPolicy()));
        }
        prompt.setTools(tools);
        planningToolsPrepared = true;
    }

    /**
     * @return 当前进程是否已经完成规划工具的运行时装配
     */
    boolean isPlanningToolsPrepared() {
        return planningToolsPrepared;
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

    /**
     * @return 本次运行创建时冻结的有效执行策略
     */
    public AgentExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    /**
     * @return 当前进程为 Run 附加的调用上下文
     */
    public AgentInvocationContext getInvocationContext() {
        return invocationContext == null ? AgentInvocationContext.empty() : invocationContext;
    }

    /**
     * 为新建或恢复的 Run 附加本次调用上下文。
     */
    public AgentRun attachInvocationContext(AgentInvocationContext context) {
        this.invocationContext = context == null ? AgentInvocationContext.empty() : context;
        return this;
    }

    /**
     * 将进程内 Run 重新关联到其持续对话上下文。
     */
    AgentRun attachConversation(AgentConversation value) {
        this.conversation = value;
        return this;
    }

    /**
     * @return 创建本 Run 的进程内持续对话上下文
     */
    AgentConversation getConversation() {
        return conversation;
    }

    /**
     * @return 本次运行的可变 Prompt，其中包含完整消息历史
     */
    public MemoryPrompt getPrompt() {
        return prompt;
    }

    /**
     * 返回可供下一轮运行复用的完整会话历史。
     *
     * <p>结果包含用户消息、模型消息和工具协议消息，但不包含 Agent 的系统指令。返回值及其中的消息
     * 都是副本，调用方可以安全地写入自己的会话存储。</p>
     */
    public List<Message> getConversationHistory() {
        List<Message> messages = prompt.getMemory().getMessages(Integer.MAX_VALUE);
        List<Message> history = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof SystemMessage)) {
                history.add(AgentMessageUtils.copyMessage(message));
            }
        }
        return Collections.unmodifiableList(history);
    }

    /**
     * @return 当前生命周期状态
     */
    public AgentRunStatus getStatus() {
        return status;
    }

    /**
     * @return 当前模型或工具执行阶段
     */
    public AgentRunPhase getPhase() {
        return phase;
    }

    /**
     * @return 尚待执行的工具调用深拷贝列表
     */
    public List<ToolCall> getPendingToolCalls() {
        return Collections.unmodifiableList(AgentMessageUtils.copyToolCalls(pendingToolCalls));
    }

    /**
     * @return 当前阻塞原因；非阻塞状态时为 null
     */
    public AgentSuspension getSuspension() {
        return suspension == null ? null : suspension.copy();
    }

    /**
     * @return 已完成或已发起的模型调用次数
     */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * @return Runner 已经推进的 step 次数，与模型调用次数相互独立
     */
    public int getStepCount() {
        return stepCount;
    }

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

    /**
     * @return Store 中最新 Checkpoint 的乐观锁版本，首次保存前为 -1
     */
    public long getVersion() {
        return version;
    }

    /**
     * @return 当前根任务累计输入 Token，包含已经汇总的子 Run
     */
    public long getInputTokens() {
        return inputTokens;
    }

    /**
     * @return 当前根任务累计输出 Token，包含已经汇总的子 Run
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
     * @return 直接父 Run ID；根 Run 返回 {@code null}
     */
    public String getParentRunId() {
        return parentRunId;
    }

    /**
     * @return 父子运行树的根 Run ID；根 Run 返回自身 ID
     */
    public String getRootRunId() {
        return rootRunId;
    }

    /**
     * @return 自动重试等延迟状态的最早可运行时间
     */
    public long getNextRunAt() {
        return nextRunAt;
    }

    /**
     * @return 已开始执行的业务工具调用数量，不包含内置规划状态转换
     */
    public int getToolCallCount() {
        return toolCallCount;
    }

    /**
     * @return 当前 Run 已安排的自动重试次数
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * @return 预算终止时命中的限制字段；未超预算时为空
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
     * @return 当前领取批次的唯一租约令牌
     */
    public String getLeaseId() {
        return leaseId;
    }

    /**
     * @return 当前执行租约到期时间
     */
    public long getLeaseUntil() {
        return leaseUntil;
    }

    /**
     * @return 当前任务计划的隔离副本；模型未创建计划时返回 null
     */
    public AgentTaskPlan getTaskPlan() {
        return taskPlan == null ? null : taskPlan.copy();
    }

    /**
     * @return 当前 Run 是否允许模型创建任务计划
     */
    public boolean isPlanningEnabled() {
        return planningEnabled;
    }

    /**
     * @return 当前 Run 在嵌套规划中的深度
     */
    public int getPlanningDepth() {
        return planningDepth;
    }

    /**
     * 返回仅基于当前 Run 本地状态计算的计划进度。
     *
     * <p>父 Run 正在等待子 Run 时，该方法不会额外查询子 Run 的真实审批或重试状态；需要跨 Run
     * 聚合状态时使用 {@link AgentRunner#getTaskProgress(String)}。</p>
     */
    public AgentTaskProgress getTaskProgress() {
        return taskPlan == null ? null : new AgentTaskProgress(taskPlan, status, suspension);
    }

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
            .updatedAt(System.currentTimeMillis())
            .nextRunAt(nextRunAt)
            .version(version)
            .parentRunId(parentRunId)
            .rootRunId(rootRunId)
            .metadata(metadata)
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

    void incrementStep() {
        stepCount++;
    }

    /**
     * 累计一次模型响应报告的 Token Usage。
     */
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

    /**
     * 保存模型已经决定、但尚未执行的 ToolCall。
     */
    void setPendingToolCalls(List<ToolCall> toolCalls) {
        this.pendingToolCalls = AgentMessageUtils.copyToolCalls(toolCalls);
    }

    /**
     * 清除已经完成处理的 ToolCall。
     */
    void clearPendingToolCalls() {
        this.pendingToolCalls.clear();
    }

    /**
     * 移除已经写入结果的首个待执行工具调用。
     */
    void removeFirstPendingToolCall() {
        if (!pendingToolCalls.isEmpty()) {
            pendingToolCalls.remove(0);
        }
    }

    /**
     * 记录一次已经开始的工具执行。
     */
    void incrementToolCallCount() {
        toolCallCount++;
    }

    /**
     * 保存工具审批结果。
     */
    void approveTool(String callId, boolean approved) {
        toolApprovals.put(callId, approved);
    }

    /**
     * 返回工具审批结果；尚未审批时返回 null。
     */
    Boolean getToolApproval(String callId) {
        return toolApprovals.get(callId);
    }

    /**
     * 保存模型创建或 Runner 推进后的不可变任务计划。
     */
    void updateTaskPlan(AgentTaskPlan value) {
        this.taskPlan = value == null ? null : value.copy();
    }

    /**
     * 将子 Run 的资源消耗累计到父 Run 的整棵任务预算。
     */
    void addChildUsage(AgentRun child) {
        if (child == null) return;
        inputTokens += child.inputTokens;
        outputTokens += child.outputTokens;
        totalTokens += child.totalTokens;
        toolCallCount += child.toolCallCount;
        retryCount += child.retryCount;
    }

    /**
     * 安排一次持久化重试。
     */
    void scheduleRetry(Throwable error, AgentRunPhase resumePhase, long runAt) {
        retryCount++;
        this.error = error;
        this.nextRunAt = runAt;
        suspend(AgentRunStatus.RETRY_SCHEDULED,
            AgentSuspension.retry(error == null ? null : error.getMessage(), resumePhase, runAt));
    }

    /**
     * 清除上一次可恢复异常。
     */
    void clearRetryError() {
        this.error = null;
    }

    /**
     * 进入指定的运行中阶段。
     */
    void moveTo(AgentRunPhase phase) {
        this.status = AgentRunStatus.RUNNING;
        this.phase = phase;
        this.suspension = null;
    }

    /**
     * 进入阻塞状态并保存恢复信息。
     */
    void suspend(AgentRunStatus status, AgentSuspension suspension) {
        this.status = status;
        this.phase = suspension.getResumePhase();
        this.suspension = suspension;
    }

    /**
     * 清除阻塞信息并回到指定执行阶段。
     */
    void resumeAt(AgentRunPhase phase) {
        this.suspension = null;
        this.status = AgentRunStatus.RUNNING;
        this.phase = phase == null ? AgentRunPhase.MODEL : phase;
        this.nextRunAt = 0;
    }

    /**
     * 进入终止状态并记录结束时间。
     */
    private void finish(AgentRunStatus status) {
        this.status = status;
        this.phase = AgentRunPhase.FINISHED;
        this.suspension = null;
        this.completedAt = System.currentTimeMillis();
    }

    /**
     * Runner 成功保存 Checkpoint 后更新本地版本。
     */
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

    /**
     * 标记 Runner 达到总 step 上限。
     */
    void markMaxStepsReached() {
        finish(AgentRunStatus.MAX_STEPS_REACHED);
    }

    /**
     * 标记预算耗尽并保存触发限制。
     */
    void markBudgetExceeded(String reason) {
        this.budgetExceededReason = reason;
        finish(AgentRunStatus.BUDGET_EXCEEDED);
    }

    /**
     * 更新当前租约信息。
     */
    void updateLease(String owner, String id, long until) {
        this.leaseOwner = owner;
        this.leaseId = id;
        this.leaseUntil = until;
    }

    /**
     * 恢复失败状态时使用的轻量异常，不尝试重建原始异常类型和堆栈。
     */
    private static final class RestoredAgentRunException extends RuntimeException {

        private RestoredAgentRunException(String errorType, String message) {
            super((errorType == null ? "" : errorType + ": ") + message);
        }
    }
}

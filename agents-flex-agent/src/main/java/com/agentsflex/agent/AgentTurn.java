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
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个 Agent 从接收一次输入到产生最终结果的可变执行轮次。
 *
 * <p>一个 Turn 可以包含多次模型迭代、工具调用、暂停恢复和自动重试。根 Turn 的输入通常来自用户，
 * 子 Turn 的输入来自父 Agent 的任务委派；每次子 Agent 调用都创建独立的 AgentTurn。该对象保存：</p>
 * <ul>
 *     <li>本轮唯一 ID 及父子 Turn 关系；</li>
 *     <li>包含 System、User、AI ToolCall 和 ToolMessage 的完整对话上下文；</li>
 *     <li>生命周期状态、模型迭代次数、最终消息和失败原因；</li>
 *     <li>业务侧附加的轮次元数据以及协作式取消标记。</li>
 * </ul>
 *
 * <p>状态转换方法仅对同包内 Runner 可见，避免业务代码直接把 Turn 标记为完成或失败。
 * AgentTurn 不是并发执行容器，同一个 Turn 不应由多个 Runner 线程同时推进。</p>
 */
public final class AgentTurn {

    private static final String CONVERSATION_ID_METADATA =
        "agentsflex.conversationId";
    private static final String CONVERSATION_BASE_MESSAGE_COUNT_METADATA =
        "agentsflex.conversationBaseMessageCount";

    /**
     * 本次运行使用的不可变 Agent 定义。
     */
    private final Agent agent;
    /**
     * 生命周期、预算、租约、规划等可持久化状态。
     */
    private final AgentTurnState state;
    /**
     * 保存模型交互历史和可用工具的 Prompt。
     */
    private final MemoryPrompt prompt;
    /**
     * 当前进程是否使用流式模型调用，不参与 Snapshot 持久化。
     */
    private transient boolean streaming;
    /**
     * 当前进程是否已经通过 AgentLoader 解析并装配规划委派目标。
     */
    private transient boolean planningToolsPrepared;
    /**
     * 失败结束时记录的原始异常。
     */
    private Throwable error;

    private AgentTurn(String id, Agent agent, MemoryPrompt prompt, long createdAt,
                      AgentExecutionPolicy executionPolicy) {
        this(agent, prompt, new AgentTurnState(id,
            effectiveExecutionPolicy(agent, executionPolicy), createdAt));
        state.setPlanningEnabled(agent.getPlanningPolicy().isEnabled());
        prepareBaseTools();
    }

    private static AgentExecutionPolicy effectiveExecutionPolicy(
        Agent agent, AgentExecutionPolicy executionPolicy) {
        if (agent == null) throw new IllegalArgumentException("agent must not be null");
        return executionPolicy == null ? agent.getExecutionPolicy() : executionPolicy;
    }

    private AgentTurn(Agent agent, MemoryPrompt prompt, AgentTurnState state) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        this.agent = agent;
        this.state = state;
        this.prompt = prompt;
        preparePrompt();
    }

    /**
     * 使用一条新的用户输入创建运行。
     *
     * @param agent     要执行的 Agent 定义
     * @param userInput 初始用户输入
     * @return 状态为 {@link AgentTurnStatus#READY} 的新运行
     */
    static AgentTurn start(Agent agent, String userInput) {
        return start(agent, new UserMessage(userInput), AgentTurnOptions.defaults());
    }

    /**
     * 使用单次运行选项创建 Turn。
     */
    static AgentTurn start(Agent agent, String userInput, AgentTurnOptions options) {
        return start(agent, new UserMessage(userInput), options);
    }

    /**
     * 使用一条结构化用户消息创建运行。
     *
     * <p>UserMessage 可以同时携带文本、图片、音频、视频、文件和消息元数据。Turn 会复制输入消息，
     * 调用方在创建后继续修改原消息不会影响本次运行及其 Snapshot。</p>
     */
    static AgentTurn start(Agent agent, UserMessage userMessage) {
        return start(agent, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 使用结构化用户消息和单次运行选项创建 Turn。
     */
    static AgentTurn start(Agent agent, UserMessage userMessage, AgentTurnOptions options) {
        return start(agent, Collections.<Message>emptyList(), userMessage, options);
    }

    /**
     * 使用已有会话历史和本轮用户消息创建独立运行。
     *
     * <p>会话历史由上层应用加载，可以来自任意业务表或消息存储。每一轮都会复制历史并创建新的
     * AgentTurn，因此已完成 Turn 的状态、预算和审计记录不会被下一轮对话修改。历史中的 SystemMessage
     * 不会被复制，系统指令始终以当前 Agent 定义为准。</p>
     */
    static AgentTurn start(Agent agent, List<? extends Message> conversationHistory,
                           UserMessage userMessage) {
        return start(agent, conversationHistory, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 使用会话历史、结构化用户消息和单次运行选项创建 Turn。
     */
    static AgentTurn start(Agent agent, List<? extends Message> conversationHistory,
                           UserMessage userMessage, AgentTurnOptions options) {
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
     * 统一应用运行选项并创建新的 READY Turn。
     */
    private static AgentTurn create(Agent agent, MemoryPrompt prompt, AgentTurnOptions options) {
        String turnId = UUID.randomUUID().toString();
        AgentTurnOptions actual = options == null ? AgentTurnOptions.defaults() : options;
        AgentTurn turn = new AgentTurn(turnId, agent, prompt, System.currentTimeMillis(),
            actual.getExecutionPolicy());
        turn.state.setMetadata(actual.getMetadata());
        turn.streaming = actual.isStreaming();
        return turn;
    }

    /**
     * 使用已有 MemoryPrompt 创建运行，适合由应用恢复已经保存的消息历史。
     *
     * <p>该方法会重新应用 Agent 的系统指令和工具列表，但会保留 Prompt 中已有的消息。</p>
     */
    static AgentTurn fromPrompt(Agent agent, MemoryPrompt prompt) {
        String turnId = UUID.randomUUID().toString();
        return new AgentTurn(turnId, agent, prompt, System.currentTimeMillis(),
            agent.getExecutionPolicy());
    }

    /**
     * 创建一个继承父 Turn 根 ID 的子 Turn。
     */
    static AgentTurn startChild(Agent agent, String userInput, AgentTurn parent) {
        AgentTurn child = start(agent, userInput);
        child.state.setParentTurnId(parent.getId());
        child.state.setRootTurnId(parent.getRootTurnId());
        // 同一进程内创建子 Turn 时继承流式调用方式；Snapshot 恢复后默认使用非流式调用。
        child.streaming = parent.streaming;
        int planningDepth = parent.getPlanningDepth() + 1;
        child.state.setPlanningDepth(planningDepth);
        child.state.setPlanningEnabled(agent.getPlanningPolicy().isEnabled()
            && parent.getAgent().getPlanningPolicy().isChildPlanningAllowed()
            && planningDepth < parent.getAgent().getPlanningPolicy().getMaxDepth());
        child.prepareBaseTools();
        return child;
    }

    /**
     * 使用持久化 Snapshot 恢复 AgentTurn。
     *
     * <p>Snapshot 保存 agentId 和 agentVersion，因此调用方必须先通过 AgentLoader 加载匹配版本的 Agent。</p>
     */
    static AgentTurn fromSnapshot(Agent agent, AgentTurnSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (!agent.getId().equals(snapshot.getAgentId())) {
            throw new IllegalArgumentException("Agent id does not match snapshot: " + snapshot.getAgentId());
        }

        MemoryPrompt prompt = new MemoryPrompt();
        for (Message message : snapshot.getState().getMessages()) {
            prompt.addMessage(message);
        }
        if (snapshot.getAgentVersion() != null
            && !agent.getVersion().equals(snapshot.getAgentVersion())) {
            throw new IllegalArgumentException("Agent version does not match snapshot: "
                + snapshot.getAgentVersion());
        }
        AgentTurnState state = snapshot.getState().mutableCopy();
        if (!StringUtil.hasText(state.getRootTurnId())) {
            state.setRootTurnId(state.getTurnId());
        }
        AgentTurn turn = new AgentTurn(agent, prompt, state);
        if (state.getErrorMessage() != null) {
            turn.error = new RestoredAgentTurnException(state.getErrorType(), state.getErrorMessage());
        }
        turn.prepareBaseTools();
        return turn;
    }

    /**
     * 将 Agent 的静态定义装配到本次运行使用的 Prompt。
     */
    private void preparePrompt() {
        if (StringUtil.hasText(agent.getInstructions())) {
            prompt.setSystemMessage(agent.getInstructions());
        }
        prepareBaseTools();
        prompt.setMaxAttachedMessageCount(agent.getMaxAttachedMessages());
    }

    /**
     * 装配不依赖运行时解析的业务工具，规划工具稍后由 Runner 统一补充。
     */
    private void prepareBaseTools() {
        prompt.setTools(new ArrayList<>(agent.getTools()));
        planningToolsPrepared = !state.isPlanningEnabled();
    }

    /**
     * 使用 AgentLoader 已解析出的完整 Agent 装配模型可见的规划工具。
     */
    void preparePlanningTools(List<Agent> delegates) {
        List<com.agentsflex.core.model.chat.tool.Tool> tools = new ArrayList<>(agent.getTools());
        if (state.isPlanningEnabled()) {
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
        if (!state.getStatus().isTerminal()) {
            state.setCancellationRequested(true);
        }
    }

    /**
     * @return 是否已经收到取消请求
     */
    public boolean isCancellationRequested() {
        return state.isCancellationRequested();
    }

    /**
     * @return 本轮的唯一 ID；在 State、Event 和 Store 契约中对应 turnId
     */
    public String getId() {
        return state.getTurnId();
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
        return state.getExecutionPolicy();
    }

    /**
     * @return 当前进程是否使用流式模型调用
     */
    boolean isStreaming() {
        return streaming;
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
    public AgentTurnStatus getStatus() {
        return state.getStatus();
    }

    /**
     * @return 当前模型或工具执行阶段
     */
    public AgentTurnPhase getPhase() {
        return state.getPhase();
    }

    /**
     * @return 尚待执行的工具调用深拷贝列表
     */
    public List<ToolCall> getPendingToolCalls() {
        return state.getPendingToolCalls();
    }

    /**
     * @return 当前阻塞原因；非阻塞状态时为 null
     */
    public AgentSuspension getSuspension() {
        return state.getSuspension();
    }

    /**
     * @return 已完成或已发起的模型调用次数
     */
    public int getIterationCount() {
        return state.getIterationCount();
    }

    /**
     * @return Runner 已经推进的 step 次数，与模型调用次数相互独立
     */
    public int getStepCount() {
        return state.getStepCount();
    }

    /**
     * @return 正常完成时的最终 AiMessage；未正常完成时为 {@code null}
     */
    public AiMessage getFinalMessage() {
        return state.getFinalMessage();
    }

    /**
     * 便捷获取最终消息的文本内容。
     *
     * @return 最终文本；尚未完成或没有最终消息时为 {@code null}
     */
    public String getFinalOutput() {
        AiMessage finalMessage = state.getFinalMessage();
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
        return state.getCreatedAt();
    }

    /**
     * @return 运行结束时间的毫秒时间戳；尚未结束时为 0
     */
    public long getCompletedAt() {
        return state.getCompletedAt();
    }

    /**
     * @return Store 中最新 Snapshot 的乐观锁版本，首次保存前为 -1
     */
    public long getVersion() {
        return state.getVersion();
    }

    /**
     * @return 当前根任务累计输入 Token，包含已经汇总的子 Turn
     */
    public long getInputTokens() {
        return state.getInputTokens();
    }

    /**
     * @return 当前根任务累计输出 Token，包含已经汇总的子 Turn
     */
    public long getOutputTokens() {
        return state.getOutputTokens();
    }

    /**
     * @return 模型报告的累计总 Token
     */
    public long getTotalTokens() {
        return state.getTotalTokens();
    }

    /**
     * @return 直接父 Turn ID；根 Turn 返回 {@code null}
     */
    public String getParentTurnId() {
        return state.getParentTurnId();
    }

    /**
     * @return Turn 树的根 Turn ID；根 Turn 返回自身 ID
     */
    public String getRootTurnId() {
        return state.getRootTurnId();
    }

    /**
     * @return 自动重试等延迟状态的最早可运行时间
     */
    public long getNextRunnableAt() {
        return state.getNextRunnableAt();
    }

    /**
     * @return 已开始执行的业务工具调用数量，不包含内置规划状态转换
     */
    public int getToolCallCount() {
        return state.getToolCallCount();
    }

    /**
     * @return 当前 Turn 已安排的自动重试次数
     */
    public int getRetryCount() {
        return state.getRetryCount();
    }

    /**
     * @return 预算终止时命中的限制字段；未超预算时为空
     */
    public String getBudgetExceededReason() {
        return state.getBudgetExceededReason();
    }

    /**
     * @return 当前执行租约的 Worker ID
     */
    public String getLeaseOwner() {
        return state.getLeaseOwner();
    }

    /**
     * @return 当前领取批次的唯一租约令牌
     */
    public String getLeaseId() {
        return state.getLeaseId();
    }

    /**
     * @return 当前执行租约到期时间
     */
    public long getLeaseUntil() {
        return state.getLeaseUntil();
    }

    /**
     * @return 当前任务计划的隔离副本；模型未创建计划时返回 null
     */
    public AgentTaskPlan getTaskPlan() {
        return state.getTaskPlan();
    }

    /**
     * @return 当前 Turn 是否允许模型创建任务计划
     */
    public boolean isPlanningEnabled() {
        return state.isPlanningEnabled();
    }

    /**
     * @return 当前 Turn 在嵌套规划中的深度
     */
    public int getPlanningDepth() {
        return state.getPlanningDepth();
    }

    /**
     * 返回仅基于当前 Turn 本地状态计算的计划进度。
     *
     * <p>父 Turn 正在等待子 Turn 时，该方法不会额外查询子 Turn 的真实审批或重试状态；需要跨 Turn
     * 聚合状态时使用 {@link AgentRunner#getTaskProgress(String)}。</p>
     */
    public AgentTaskProgress getTaskProgress() {
        AgentTaskPlan taskPlan = state.getTaskPlan();
        return taskPlan == null ? null
            : new AgentTaskProgress(taskPlan, state.getStatus(), state.getSuspension());
    }

    /**
     * 返回只读元数据视图。
     *
     * @return 不允许直接修改的元数据 Map
     */
    public Map<String, Object> getMetadata() {
        return state.getMetadata();
    }

    /**
     * 返回通过 Runner 的可选 ChatMemory 集成绑定的业务会话 ID。
     *
     * <p>未使用 {@code chatMemoryProvider} 创建的 Turn 返回 {@code null}。会话 ID 仅用于定位业务
     * ChatMemory，不替代 Turn ID，也不参与 Agent 执行状态判断。</p>
     */
    public String getConversationId() {
        Object value = state.getMetadata().get(CONVERSATION_ID_METADATA);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 返回创建 Turn 时已经从 ChatMemory 载入的模型消息数量。
     *
     * <p>Runner 以该位置为边界，只把本轮新增消息投影回 ChatMemory，避免重复写入历史。</p>
     */
    int getConversationBaseMessageCount() {
        Object value = state.getMetadata().get(CONVERSATION_BASE_MESSAGE_COUNT_METADATA);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 将本次 Turn 绑定到业务会话；绑定信息随 Snapshot 持久化。
     */
    void bindConversation(String conversationId, int baseMessageCount) {
        if (!StringUtil.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (baseMessageCount < 0) {
            throw new IllegalArgumentException("baseMessageCount must not be negative");
        }
        state.putMetadata(CONVERSATION_ID_METADATA, conversationId);
        state.putMetadata(CONVERSATION_BASE_MESSAGE_COUNT_METADATA, baseMessageCount);
    }

    /**
     * 添加或覆盖一项业务运行元数据。
     */
    public void putMetadata(String key, Object value) {
        state.putMetadata(key, value);
    }

    /**
     * 生成与当前可变状态隔离的 Snapshot。
     */
    public AgentTurnSnapshot toSnapshot() {
        // getMessages() 可能受附加消息数量限制；Snapshot 必须保存 Memory 中的完整历史。
        List<Message> messages = prompt.getMemory().getMessages(Integer.MAX_VALUE);
        SystemMessage systemMessage = prompt.getSystemMessage();
        if (systemMessage != null && (messages.isEmpty() || !(messages.get(0) instanceof SystemMessage))) {
            messages.add(0, systemMessage);
        }
        state.setMessages(messages);
        state.setError(error == null ? null : error.getClass().getName(),
            error == null ? null : error.getMessage());
        state.setUpdatedAt(System.currentTimeMillis());
        return AgentTurnSnapshot.of(agent.getId(), agent.getVersion(), state);
    }

    /**
     * 标记第一次开始执行，并返回是否需要发送 onTurnStart 事件。
     */
    boolean markStarted() {
        if (state.isStarted()) {
            return false;
        }
        state.setStarted(true);
        state.setStatus(AgentTurnStatus.RUNNING);
        return true;
    }

    /**
     * 增加一次模型迭代计数。
     */
    void incrementIteration() {
        state.incrementIterationCount();
    }

    void incrementStep() {
        state.incrementStepCount();
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
        long input = promptTokens == null ? 0 : promptTokens;
        long output = completionTokens == null ? 0 : completionTokens;
        long total = messageTotalTokens == null ? input + output : messageTotalTokens;
        state.addUsage(input, output, total);
    }

    /**
     * 保存模型已经决定、但尚未执行的 ToolCall。
     */
    void setPendingToolCalls(List<ToolCall> toolCalls) {
        state.setPendingToolCalls(toolCalls);
    }

    /**
     * 清除已经完成处理的 ToolCall。
     */
    void clearPendingToolCalls() {
        state.clearPendingToolCalls();
    }

    /**
     * 移除已经写入结果的首个待执行工具调用。
     */
    void removeFirstPendingToolCall() {
        state.removeFirstPendingToolCall();
    }

    /**
     * 记录一次已经开始的工具执行。
     */
    void incrementToolCallCount() {
        state.incrementToolCallCount();
    }

    /**
     * 保存工具审批结果。
     */
    void approveTool(String callId, boolean approved) {
        state.approveTool(callId, approved);
    }

    /**
     * 返回工具审批结果；尚未审批时返回 null。
     */
    Boolean getToolApproval(String callId) {
        return state.getToolApproval(callId);
    }

    /**
     * 保存模型创建或 Runner 推进后的不可变任务计划。
     */
    void updateTaskPlan(AgentTaskPlan value) {
        state.setTaskPlan(value);
    }

    /**
     * 将子 Turn 的资源消耗累计到父 Turn 的整棵任务预算。
     */
    void addChildUsage(AgentTurn child) {
        if (child == null) return;
        state.addChildUsage(child.state);
    }

    /**
     * 安排一次持久化重试。
     */
    void scheduleRetry(Throwable error, AgentTurnPhase resumePhase, long runAt) {
        state.incrementRetryCount();
        this.error = error;
        state.setNextRunnableAt(runAt);
        suspend(AgentTurnStatus.RETRY_SCHEDULED,
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
    void moveTo(AgentTurnPhase phase) {
        state.setStatus(AgentTurnStatus.RUNNING);
        state.setPhase(phase);
        state.setSuspension(null);
    }

    /**
     * 进入阻塞状态并保存恢复信息。
     */
    void suspend(AgentTurnStatus status, AgentSuspension suspension) {
        state.setStatus(status);
        state.setPhase(suspension.getResumePhase());
        state.setSuspension(suspension);
    }

    /**
     * 清除阻塞信息并回到指定执行阶段。
     */
    void resumeAt(AgentTurnPhase phase) {
        state.setSuspension(null);
        state.setStatus(AgentTurnStatus.RUNNING);
        state.setPhase(phase == null ? AgentTurnPhase.MODEL : phase);
        state.setNextRunnableAt(0);
    }

    /**
     * 进入终止状态并记录结束时间。
     */
    private void finish(AgentTurnStatus status) {
        state.setStatus(status);
        state.setPhase(AgentTurnPhase.FINISHED);
        state.setSuspension(null);
        state.setCompletedAt(System.currentTimeMillis());
    }

    /**
     * Runner 成功保存 Snapshot 后更新本地版本。
     */
    void updateVersion(long version) {
        state.setVersion(version);
    }

    /**
     * 工具执行完成后保持运行态，等待下一个模型回合。
     */
    void markRunning() {
        moveTo(AgentTurnPhase.MODEL);
    }

    /**
     * 标记正常完成并保存最终模型消息。
     */
    void markCompleted(AiMessage finalMessage) {
        state.setFinalMessage(finalMessage);
        finish(AgentTurnStatus.COMPLETED);
    }

    /**
     * 标记失败并保存原始异常。
     */
    void markFailed(Throwable error) {
        this.error = error;
        finish(AgentTurnStatus.FAILED);
    }

    /**
     * 标记取消完成。
     */
    void markCancelled() {
        finish(AgentTurnStatus.CANCELLED);
    }

    /**
     * 标记因达到最大模型迭代次数而结束。
     */
    void markMaxIterationsReached() {
        finish(AgentTurnStatus.MAX_ITERATIONS_REACHED);
    }

    /**
     * 标记 Runner 达到总 step 上限。
     */
    void markMaxStepsReached() {
        finish(AgentTurnStatus.MAX_STEPS_REACHED);
    }

    /**
     * 标记预算耗尽并保存触发限制。
     */
    void markBudgetExceeded(String reason) {
        state.setBudgetExceededReason(reason);
        finish(AgentTurnStatus.BUDGET_EXCEEDED);
    }

    /**
     * 更新当前租约信息。
     */
    void updateLease(String owner, String id, long until) {
        state.setLeaseOwner(owner);
        state.setLeaseId(id);
        state.setLeaseUntil(until);
    }

    /**
     * 恢复失败状态时使用的轻量异常，不尝试重建原始异常类型和堆栈。
     */
    private static final class RestoredAgentTurnException extends RuntimeException {

        private RestoredAgentTurnException(String errorType, String message) {
            super((errorType == null ? "" : errorType + ": ") + message);
        }
    }
}

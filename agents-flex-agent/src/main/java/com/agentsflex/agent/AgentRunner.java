/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEventListener;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentModelCallChain;
import com.agentsflex.agent.middleware.AgentStepChain;
import com.agentsflex.agent.middleware.AgentToolCallChain;
import com.agentsflex.agent.loader.AgentLoader;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentTurnStore;
import com.agentsflex.agent.store.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.store.ParentChildTurnSnapshots;
import com.agentsflex.agent.tool.AgentFormDefinition;
import com.agentsflex.agent.tool.AgentToolProgressEmitter;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.AgentFormRequiredException;
import com.agentsflex.agent.tool.AgentUserInputTool;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.agent.tool.ToolErrorStrategy;
import com.agentsflex.agent.task.AgentTaskProgress;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.ChatMemoryProvider;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutor;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 创建、推进、暂停和恢复 {@link AgentTurn} 的核心执行器。
 *
 * <p>Runner 可以理解为一个可持久化的状态机执行器。{@link Agent} 提供模型、指令、工具和执行
 * 策略，{@link AgentTurn} 保存某个 Agent 一次输入到最终结果的可变状态，Runner 根据 Turn 的
 * {@link AgentTurnStatus} 和 {@link AgentTurnPhase} 决定下一步调用模型、执行工具、等待外部事件，
 * 或结束本轮。根 Turn 通常由用户消息触发，子 Turn 由父 Agent 委派触发。</p>
 *
 * <p>一次标准执行由三层组成：</p>
 * <ol>
 *     <li>{@link #runUntilBlocked(AgentTurn)} 决定是否继续循环；</li>
 *     <li>{@link #step(AgentTurn)} 完成取消、Lease、预算、规划和 Middleware 等通用检查；</li>
 *     <li>内置 ToolCall 状态机根据当前 Phase 推进模型调用或工具执行。</li>
 * </ol>
 *
 * <p>内置状态机使用模型原生 ToolCall。模型产生 ToolCall 后，Runner 先把调用及参数保存为
 * Snapshot，再逐个完成审批、工具执行和 ToolMessage 写入。审批恢复时因此可以继续执行已经确认的
 * 原始 ToolCall，而不需要重新请求模型生成参数。</p>
 *
 * <p>Runner 同时负责预算检查、自动重试、暂停恢复、任务规划、父子 Turn 协调和生命周期事件。
 * 所有需要跨进程恢复的状态最终通过 {@link AgentTurnStore} 持久化；Runner 自身不长期保存任务状态，
 * 因而通常作为应用级对象复用。</p>
 *
 * <p>直接调用 {@code run(...)} 会在当前线程推进子 Turn；分布式长任务应先调用 {@code start(...)}
 * 保存 READY Snapshot，再由 AgentWorker 通过租约领取。不要让两个线程直接推进同一个
 * AgentTurn 对象。</p>
 */
public final class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final String INPUT_TARGET_METADATA = "inputTarget";
    private static final String TOOL_INPUT_TARGET = "TOOL";

    /**
     * 保存 Snapshot、取消标记和 Worker 租约的 Turn 存储。
     */
    private final AgentTurnStore turnStore;
    /**
     * 创建新任务和恢复旧任务时解析完整 Agent 的加载器。
     */
    private final AgentLoader agentLoader;
    /**
     * 统一构造并同步发布 AgentEvent 的包内组件。
     */
    private final AgentEventPublisher eventPublisher;
    /**
     * 集中处理任务规划状态转换的包内组件。
     */
    private final AgentRunnerPlanning planning;
    /**
     * 统一模型调用、Token 统计和事件发布的适配器。
     */
    private final AgentModelInvoker modelInvoker;
    /**
     * 可选的业务会话消息投影。未配置 Provider 时为空操作，现有显式传历史消息的 API 不受影响。
     */
    private final AgentRunnerChatMemory chatMemory;
    /**
     * 标识当前线程正在代表哪个 Worker 推进已领取的 Turn。
     */
    private final ThreadLocal<String> activeWorkerId = new ThreadLocal<>();
    /**
     * 当前 Worker 本次领取得到的唯一租约令牌。
     *
     * <p>仅校验 workerId 无法区分同名 Worker 的两次领取；leaseId 用作 fencing token，阻止租约已经
     * 失效的旧执行者继续提交 Snapshot。</p>
     */
    private final ThreadLocal<String> activeLeaseId = new ThreadLocal<>();
    /**
     * 当前线程正在执行的 Step 结束后再发布的观察事件。
     *
     * <p>状态和 Snapshot 仍在原位置立即更新；这里只延后 TURN_SUSPENDED 等通知，保证监听器先看到
     * STEP_COMPLETED，再看到本步骤产生的 Turn 状态事件。</p>
     */
    private final ThreadLocal<List<Runnable>> afterStepEvents = new ThreadLocal<>();
    /** 同一 Runner 内按 conversationId 串行创建初始 Turn，避免检查与保存之间出现竞态。 */
    private final ConcurrentMap<String, Object> conversationLocks = new ConcurrentHashMap<>();

    /**
     * 创建全部使用进程内依赖的 Runner，适合测试和单实例试用。
     */
    public AgentRunner() {
        this(new InMemoryAgentTurnStore(), new InMemoryAgentLoader());
    }

    /**
     * 创建可按需替换 Store 和 Loader 的 Runner 构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建自定义 TurnStore 和 AgentLoader 的 Runner。
     */
    public AgentRunner(AgentTurnStore turnStore, AgentLoader agentLoader) {
        this(turnStore, agentLoader, null);
    }

    private AgentRunner(AgentTurnStore turnStore, AgentLoader agentLoader,
                        ChatMemoryProvider chatMemoryProvider) {
        if (turnStore == null || agentLoader == null) {
            throw new IllegalArgumentException(
                "AgentRunner dependencies must not be null");
        }
        this.turnStore = turnStore;
        this.agentLoader = agentLoader;
        this.eventPublisher = new AgentEventPublisher();
        this.planning = new AgentRunnerPlanning(this, agentLoader, eventPublisher);
        this.modelInvoker = new AgentModelInvoker(eventPublisher);
        this.chatMemory = new AgentRunnerChatMemory(chatMemoryProvider);
    }

    /**
     * AgentRunner 依赖构建器。
     *
     * <p>未显式配置的组件使用进程内实现，适合测试和本地开发。多实例部署应替换 TurnStore 和
     * AgentLoader。AgentLoader 必须返回包含完整工具集合的可执行 Agent。</p>
     */
    public static final class Builder {
        private AgentTurnStore turnStore = new InMemoryAgentTurnStore();
        private AgentLoader agentLoader = new InMemoryAgentLoader();
        private ChatMemoryProvider chatMemoryProvider;

        /**
         * 设置 Snapshot 与租约存储。
         */
        public Builder turnStore(AgentTurnStore value) {
            turnStore = value;
            return this;
        }

        /**
         * 设置完整 Agent 加载器。
         */
        public Builder agentLoader(AgentLoader value) {
            agentLoader = value;
            return this;
        }

        /**
         * 设置按业务会话 ID 定位 ChatMemory 的 {@link ChatMemoryProvider}。
         *
         * <p>配置后可以使用带 {@code conversationId} 的 start/run 重载。Runner 从
         * {@link ChatMemory#getModelMessages(int)} 读取模型历史，并在每次 Snapshot 成功保存后把本轮
         * 消息和审批卡片幂等投影回同一个 ChatMemory。未配置时不会读写任何业务会话。</p>
         */
        public Builder chatMemoryProvider(ChatMemoryProvider value) {
            chatMemoryProvider = value;
            return this;
        }

        /**
         * 校验全部依赖并创建 Runner。
         */
        public AgentRunner build() {
            return new AgentRunner(turnStore, agentLoader, chatMemoryProvider);
        }
    }

    /**
     * @return Runner 使用的 TurnStore
     */
    public AgentTurnStore getTurnStore() {
        return turnStore;
    }

    /**
     * @return Runner 使用的 AgentLoader
     */
    public AgentLoader getAgentLoader() {
        return agentLoader;
    }

    /**
     * 添加统一事件监听器。
     *
     * <p>监听器在发布线程同步执行，只用于观察；单个监听器异常会被隔离，不会让 Turn 失败。</p>
     */
    public AgentRunner addEventListener(AgentEventListener listener) {
        eventPublisher.addListener(listener);
        return this;
    }

    /**
     * 删除已经注册的统一事件监听器。
     */
    public AgentRunner removeEventListener(AgentEventListener listener) {
        eventPublisher.removeListener(listener);
        return this;
    }

    /**
     * 创建 Turn 并同步推进到终止或阻塞状态。
     *
     * <p>该便捷入口等价于先调用 {@link #start(Agent, String)}，再调用
     * {@link #runUntilBlocked(AgentTurn)}。返回值不一定已经完成，也可能正在等待审批、用户输入、
     * 子 Agent 或重试时间。</p>
     */
    public AgentTurn run(Agent agent, String userInput) {
        return run(start(agent, userInput));
    }

    /**
     * 使用单次运行策略和元数据创建并执行任务。
     */
    public AgentTurn run(Agent agent, String userInput, AgentTurnOptions options) {
        return run(start(agent, userInput, options));
    }

    /**
     * 使用一条可包含文本、图片、音频、视频和文件的用户消息执行 Turn。
     */
    public AgentTurn run(Agent agent, UserMessage userMessage) {
        return run(start(agent, userMessage));
    }

    /**
     * 使用结构化用户消息和单次运行选项执行 Turn。
     */
    public AgentTurn run(Agent agent, UserMessage userMessage, AgentTurnOptions options) {
        return run(start(agent, userMessage, options));
    }

    /**
     * 加载当前生效的 Agent，并从业务 ChatMemory 读取历史后执行一轮文本请求。
     */
    public AgentTurn run(String agentId, String conversationId, String userInput) {
        return run(agentId, conversationId, new UserMessage(userInput),
            AgentTurnOptions.defaults());
    }

    /**
     * 加载当前生效的 Agent，并使用单次运行选项执行一轮文本请求。
     */
    public AgentTurn run(String agentId, String conversationId, String userInput,
                         AgentTurnOptions options) {
        return run(agentId, conversationId, new UserMessage(userInput), options);
    }

    /**
     * 加载当前生效的 Agent，并从业务 ChatMemory 读取历史后执行一轮结构化请求。
     */
    public AgentTurn run(String agentId, String conversationId, UserMessage userMessage) {
        return run(agentId, conversationId, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 加载当前生效的 Agent，并从业务 ChatMemory 读取历史后执行一轮结构化请求。
     *
     * <p>新 Turn 使用 {@link AgentLoader#loadActive(String)} 返回的当前生效版本；初始 Snapshot 保存
     * 实际加载到的 agentId 和 version，后续恢复仍按精确版本加载。</p>
     */
    public AgentTurn run(String agentId, String conversationId, UserMessage userMessage,
                         AgentTurnOptions options) {
        return run(start(agentId, conversationId, userMessage, options));
    }

    /**
     * 从业务 ChatMemory 读取会话历史并执行一轮文本请求。
     */
    public AgentTurn run(Agent agent, String conversationId, String userInput) {
        return run(agent, conversationId, new UserMessage(userInput),
            AgentTurnOptions.defaults());
    }

    /**
     * 从业务 ChatMemory 读取会话历史，并使用单次运行选项执行一轮文本请求。
     */
    public AgentTurn run(Agent agent, String conversationId, String userInput,
                         AgentTurnOptions options) {
        return run(agent, conversationId, new UserMessage(userInput), options);
    }

    /**
     * 从业务 ChatMemory 读取会话历史并执行一轮结构化请求。
     */
    public AgentTurn run(Agent agent, String conversationId, UserMessage userMessage) {
        return run(agent, conversationId, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 从业务 ChatMemory 读取会话历史并执行一轮结构化请求。
     */
    public AgentTurn run(Agent agent, String conversationId, UserMessage userMessage,
                         AgentTurnOptions options) {
        return run(start(agent, conversationId, userMessage, options));
    }

    /**
     * 使用已有会话历史和本轮结构化消息创建并执行新的 Turn。
     */
    public AgentTurn run(Agent agent, List<? extends Message> conversationHistory,
                         UserMessage userMessage) {
        return run(start(agent, conversationHistory, userMessage));
    }

    /**
     * 使用已有会话历史、本轮结构化消息和单次运行选项创建并执行新的 Turn。
     */
    public AgentTurn run(Agent agent, List<? extends Message> conversationHistory,
                         UserMessage userMessage, AgentTurnOptions options) {
        return run(start(agent, conversationHistory, userMessage, options));
    }

    /**
     * 创建并保存一个尚未执行的 Turn。
     */
    public AgentTurn start(Agent agent, String userInput) {
        return start(agent, userInput, AgentTurnOptions.defaults());
    }

    /**
     * 创建并保存一个带有运行时覆盖参数的任务。
     */
    public AgentTurn start(Agent agent, String userInput, AgentTurnOptions options) {
        return start(agent, new UserMessage(userInput), options);
    }

    /**
     * 创建并保存一个使用结构化用户消息、尚未执行的 Turn。
     */
    public AgentTurn start(Agent agent, UserMessage userMessage) {
        return start(agent, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 创建并保存一个使用结构化用户消息和运行时覆盖参数的 Turn。
     */
    public AgentTurn start(Agent agent, UserMessage userMessage, AgentTurnOptions options) {
        return start(agent, Collections.<Message>emptyList(), userMessage, options);
    }

    /**
     * 加载当前生效的 Agent，并从业务 ChatMemory 读取历史后创建文本 Turn。
     */
    public AgentTurn start(String agentId, String conversationId, String userInput) {
        return start(agentId, conversationId, new UserMessage(userInput),
            AgentTurnOptions.defaults());
    }

    /**
     * 加载当前生效的 Agent，并使用单次运行选项创建文本 Turn。
     */
    public AgentTurn start(String agentId, String conversationId, String userInput,
                           AgentTurnOptions options) {
        return start(agentId, conversationId, new UserMessage(userInput), options);
    }

    /**
     * 加载当前生效的 Agent，并从业务 ChatMemory 读取历史后创建结构化 Turn。
     */
    public AgentTurn start(String agentId, String conversationId, UserMessage userMessage) {
        return start(agentId, conversationId, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 加载当前生效的 Agent，并从业务 ChatMemory 读取历史后创建结构化 Turn。
     */
    public AgentTurn start(String agentId, String conversationId, UserMessage userMessage,
                           AgentTurnOptions options) {
        return start(loadActiveAgent(agentId), conversationId, userMessage, options);
    }

    /**
     * 从业务 ChatMemory 读取会话历史并创建一个尚未执行的文本 Turn。
     */
    public AgentTurn start(Agent agent, String conversationId, String userInput) {
        return start(agent, conversationId, new UserMessage(userInput),
            AgentTurnOptions.defaults());
    }

    /**
     * 从业务 ChatMemory 读取会话历史并创建带运行选项的文本 Turn。
     */
    public AgentTurn start(Agent agent, String conversationId, String userInput,
                           AgentTurnOptions options) {
        return start(agent, conversationId, new UserMessage(userInput), options);
    }

    /**
     * 从业务 ChatMemory 读取会话历史并创建一个尚未执行的结构化 Turn。
     */
    public AgentTurn start(Agent agent, String conversationId, UserMessage userMessage) {
        return start(agent, conversationId, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 从业务 ChatMemory 读取模型可见历史并创建 Turn。
     *
     * <p>页面专用消息不会进入 Prompt。Turn 创建后，本轮新增消息会在 Snapshot 保存成功后投影回
     * ChatMemory；投影失败不会改变 Turn 状态，并会在后续保存或恢复时重试。</p>
     */
    public AgentTurn start(Agent agent, String conversationId, UserMessage userMessage,
                           AgentTurnOptions options) {
        if (!chatMemory.isEnabled()) {
            throw new IllegalStateException(
                "ChatMemoryProvider must be configured for conversation APIs");
        }
        if (!StringUtil.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        Object lock = conversationLocks.computeIfAbsent(conversationId, key -> new Object());
        synchronized (lock) {
            AgentTurnSnapshot active = turnStore.findActiveTurn(conversationId);
            if (active != null) {
                throw new AgentConversationBusyException(conversationId,
                    active.getState().getTurnId(), active.getState().getStatus());
            }
            prepareAgent(agent);
            List<Message> history = chatMemory.loadModelHistory(
                conversationId, agent.getMaxAttachedMessages());
            AgentTurn turn = AgentTurn.start(agent, history, userMessage, options);
            turn.bindConversation(conversationId, history.size());
            prepareTurn(turn);
            saveInitialConversationSnapshot(turn);
            return turn;
        }
    }

    private void saveInitialConversationSnapshot(AgentTurn turn) {
        synchronized (turn) {
            AgentTurnSnapshot saved = turnStore.saveNewConversationTurn(turn.toSnapshot());
            turn.updateVersion(saved.getState().getVersion());
            chatMemory.sync(turn);
            eventPublisher.notifySnapshotSaved(turn, saved);
        }
    }

    /**
     * 使用已有会话历史和本轮结构化消息创建并保存新的 Turn。
     */
    public AgentTurn start(Agent agent, List<? extends Message> conversationHistory,
                           UserMessage userMessage) {
        return start(agent, conversationHistory, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 使用已有会话历史、本轮结构化消息和运行时覆盖参数创建并保存新的 Turn。
     *
     * <p>这是携带历史消息创建 Turn 的统一入口。方法只建立可恢复的 READY 状态，不调用模型；
     * 初始 Snapshot 成功后，Turn 才会返回给调用方或后台调度器。</p>
     */
    public AgentTurn start(Agent agent, List<? extends Message> conversationHistory,
                           UserMessage userMessage, AgentTurnOptions options) {
        // 先准备 Agent 和规划工具，再创建 Turn，确保初始 Snapshot 已包含完整可执行状态。
        prepareAgent(agent);
        AgentTurn turn = AgentTurn.start(agent, conversationHistory, userMessage, options);
        prepareTurn(turn);
        // 初始 Snapshot 使任务在第一次模型调用之前就可以被 Worker 发现和恢复。
        saveSnapshot(turn);
        return turn;
    }

    /**
     * 推进已经创建的 Turn，语义等同于 {@link #runUntilBlocked(AgentTurn)}。
     */
    public AgentTurn run(AgentTurn turn) {
        return runUntilBlocked(turn);
    }

    /**
     * 持续推进，直到根任务终止或等待外部事件。
     *
     * <p>“阻塞”不是失败，而是已经保存了恢复所需状态并等待外部条件。典型阻塞状态包括等待用户输入、
     * 工具审批、子 Turn 和重试时间。终止状态或阻塞状态到达后，本方法都会正常返回，由调用方读取
     * {@link AgentTurn#getStatus()} 决定后续动作。</p>
     *
     * <p>同步调用会在当前线程继续执行规划产生的子 Turn，并在子 Turn 终止后自动恢复父 Turn。由
     * AgentWorker 持租约调用时只推进当前已领取 Turn，子 Turn 留给 Store 后续独立领取。</p>
     *
     * @return 最新父 Turn；阻塞状态表示需要审批、用户输入、子 Turn 或重试时间
     */
    public AgentTurn runUntilBlocked(AgentTurn turn) {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        AgentTurn current = turn;
        ensurePreparedAndSnapshotSaved(current);
        refreshCancellation(current);
        while (true) {
            // 普通状态持续单步推进；若阻塞期间收到取消信号，也要再执行一步完成 CANCELLED 落盘。
            while (!current.getStatus().isTerminal()
                && (!current.getStatus().isBlocked() || current.isCancellationRequested())) {
                step(current);
            }

            // 没有活动子任务时，当前 Turn 已经到达本次调用的返回边界。
            AgentTurn child = planning.currentChild(current);
            if (child == null || current.isCancellationRequested()) return current;

            // Worker 只能推进自己通过 Store 领取的 Turn，子 Turn 必须另行领取并获得独立租约。
            if (activeWorkerId.get() != null) return current;

            // 同步模式递归执行子 Turn；子 Turn 若再次阻塞，则保持父 Turn 的 WAITING_FOR_CHILD 状态返回。
            child = runUntilBlocked(child);
            if (!child.getStatus().isTerminal()) return current;

            // 子 Turn 终止后把结果写回父 Turn，再从父 Turn 原来的恢复 Phase 继续外层循环。
            current = resumeParentFromChild(child);
            if (current == null) return turn;
        }
    }

    /**
     * 将取消请求持久化到 Store，并返回包含最新取消标记的 Turn。
     *
     * <p>该方法可以取消等待状态或由 Worker 执行的任务。取消是协作式的，正在阻塞的模型请求或
     * Tool 方法不会被强制中断，Runner 会在下一个安全边界终止运行。</p>
     */
    public AgentTurn requestCancellation(String turnId) {
        boolean requested = turnStore.requestCancellation(turnId);
        AgentTurn turn = restore(turnId);
        AgentTurn child = planning.currentChild(turn);
        if (child != null && !child.getStatus().isTerminal()) {
            turnStore.requestCancellation(child.getId());
        }
        if (requested) {
            eventPublisher.notifyCancellationRequested(turn);
        }
        return turn;
    }

    /**
     * 从 Store 恢复最新 Snapshot。
     *
     * <p>方法按照快照中的 agentId 和 agentVersion 加载匹配定义，不使用当前生效版本。</p>
     */
    public AgentTurn restore(String turnId) {
        // Snapshot 是恢复状态的事实来源；运行时对象不能从旧 JVM 内存中获取。
        AgentTurnSnapshot snapshot = turnStore.load(turnId);
        if (snapshot == null) {
            throw new IllegalStateException("AgentTurn snapshot not found: " + turnId);
        }
        // 必须加载创建 Turn 时记录的版本，避免用最新配置解释待执行 ToolCall。
        Agent agent = agentLoader.load(snapshot.getAgentId(), snapshot.getAgentVersion());
        if (agent == null) {
            throw new IllegalStateException("Agent cannot be loaded: " + snapshot.getAgentId()
                + ", version=" + snapshot.getAgentVersion());
        }
        AgentTurn turn = AgentTurn.fromSnapshot(agent, snapshot);
        chatMemory.sync(turn);
        return turn;
    }

    /**
     * 从最新 Snapshot 恢复指定 Turn 并推进到下一个稳定边界。
     */
    public AgentTurn runUntilBlocked(String turnId) {
        return runUntilBlocked(restore(turnId));
    }

    /**
     * 将 Turn 置为等待外部事件的状态并立即保存。
     *
     * <p>Suspension 同时记录等待类型、关联 ID 和恢复 Phase。外部命令只有匹配这些信息才能恢复，
     * 从而避免把某次审批决定误用到另一个 ToolCall。</p>
     */
    public AgentTurn suspend(AgentTurn turn, AgentSuspension suspension) {
        if (turn == null || suspension == null) {
            throw new IllegalArgumentException("turn and suspension must not be null");
        }
        if (turn.getStatus().isTerminal()) {
            throw new IllegalStateException("terminal turn cannot be suspended: " + turn.getStatus());
        }
        assertLeaseOwnership(turn);
        turn.suspend(blockedStatusFor(suspension.getType()), suspension);
        saveSnapshot(turn);
        publishAfterStep(() -> eventPublisher.notifyTurnSuspended(turn, suspension));
        return turn;
    }

    /**
     * 应用外部命令并在当前线程继续推进。
     *
     * <p>根 Turn 正在等待规划子 Turn 时，命令会自动路由到实际阻塞的子 Turn；子 Turn 终止后再恢复并
     * 推进父 Turn。</p>
     */
    public AgentTurn resume(AgentTurn turn, AgentResumeCommand command) {
        AgentTurn child = planning.currentChild(turn);
        if (child == null) return runUntilBlocked(submitResume(turn, command));
        AgentTurn resumedChild = resume(child, command);
        if (!resumedChild.getStatus().isTerminal()) return turn;
        AgentTurn parent = resumeParentFromChild(resumedChild);
        return parent == null ? turn : runUntilBlocked(parent);
    }

    /**
     * 提交恢复命令并保存为可运行状态，但不在当前线程继续执行。
     *
     * <p>事件消费者可以使用该方法唤醒任务，再由 AgentWorker 通过租约领取执行。</p>
     */
    public AgentTurn submitResume(AgentTurn turn, AgentResumeCommand command) {
        if (turn == null || command == null) {
            throw new IllegalArgumentException("turn and command must not be null");
        }
        if (!turn.getStatus().isBlocked()) {
            throw new IllegalStateException("turn is not blocked: " + turn.getStatus());
        }
        assertLeaseOwnership(turn);
        AgentSuspension suspension = turn.getSuspension();
        // 先按 Suspension 类型校验并应用命令，任何不匹配的命令都不能改变运行状态。
        applyResumeCommand(turn, suspension, command);
        // 恢复到暂停前保存的模型或工具阶段，不从任务开头重新执行。
        AgentTurnPhase resumePhase = suspension.getResumePhase();
        if (suspension.getType() == AgentSuspensionType.USER_INPUT
            && StringUtil.hasText(suspension.getCorrelationId())
            && turn.getPendingToolCalls().isEmpty()) {
            resumePhase = AgentTurnPhase.MODEL;
        }
        turn.resumeAt(resumePhase);
        saveSnapshot(turn);
        eventPublisher.notifyTurnResumed(turn, command);
        return turn;
    }

    /**
     * 恢复指定 ID 的阻塞 Turn，并同步推进到下一个稳定边界。
     */
    public AgentTurn resume(String turnId, AgentResumeCommand command) {
        return resume(restore(turnId), command);
    }

    /**
     * 恢复指定 ID 的 Turn 但不继续执行；规划场景会自动路由到当前活动子 Turn。
     */
    public AgentTurn submitResume(String turnId, AgentResumeCommand command) {
        AgentTurn turn = restore(turnId);
        AgentTurn child = planning.currentChild(turn);
        return submitResume(child == null ? turn : child, command);
    }

    /**
     * 保存稳定状态并更新本地乐观锁版本。
     *
     * <p>如果当前线程代表 Worker 执行，保存前会校验 workerId、leaseId 和租约时间。版本冲突直接
     * 抛出异常，由调用方恢复最新快照后决定是否继续。</p>
     *
     * <p>Store 返回的 Snapshot 包含新版本号和可能由其他控制面原子写入的取消标记。监听器只在保存
     * 成功后收到通知，因此看到的是已经持久化的稳定状态。</p>
     */
    public AgentTurnSnapshot saveSnapshot(AgentTurn turn) {
        synchronized (turn) {
            // 同步块保证同一 JVM 中 Snapshot 构造、Store CAS 和本地版本更新不可交错。
            assertLeaseOwnership(turn);
            AgentTurnSnapshot saved = turnStore.save(turn.toSnapshot(), turn.getVersion());
            turn.updateVersion(saved.getState().getVersion());
            if (saved.getState().isCancellationRequested()) {
                turn.requestCancellation();
            }
            chatMemory.sync(turn);
            eventPublisher.notifySnapshotSaved(turn, saved);
            return saved;
        }
    }

    /**
     * 推进当前模型或工具步骤。
     *
     * <p>一次调用最多调用模型一次，但可以顺序处理该模型回合产生的全部 ToolCall。方法返回
     * {@link AgentStepResult} 描述本步结果；是否继续下一步由 {@link #runUntilBlocked(AgentTurn)} 决定。</p>
     *
     * <p>Turn 是 Step 的生命周期容器：首次推进先发布 TURN_STARTED，再发布 STEP_STARTED；终止
     * Step 先发布 STEP_COMPLETED，随后发布对应的 Turn 终止事件。这样监听器收到终止事件后，不会再
     * 收到该 Turn 的步骤事件。</p>
     */
    public AgentStepResult step(AgentTurn turn) {
        // 在任何 Step 事件之前完成首次 Turn 状态转换，保持 Turn > Step 的生命周期嵌套关系。
        validateStep(turn);
        ensurePreparedAndSnapshotSaved(turn);
        // 无效 Worker 不得先发布生命周期事件；真正执行前 stepCore 还会再次校验租约和取消信号。
        assertLeaseOwnership(turn);
        refreshCancellation(turn);
        if (turn.markStarted()) {
            eventPublisher.notifyTurnStart(turn);
        }
        String entryBudgetReason = budgetExceededReason(turn, false);
        boolean maxStepsReached = !turn.isCancellationRequested()
            && !turn.getStatus().isBlocked()
            && entryBudgetReason == null
            && turn.getStepCount() >= turn.getExecutionPolicy().getMaxSteps();
        if (!turn.isCancellationRequested() && !turn.getStatus().isBlocked()
            && entryBudgetReason == null && !maxStepsReached) {
            // STEP_STARTED 和 STEP_COMPLETED 对同一次推进展示相同的 1-based stepCount。
            turn.incrementStep();
        }

        List<Runnable> parentEvents = afterStepEvents.get();
        List<Runnable> deferredEvents = new ArrayList<>();
        afterStepEvents.set(deferredEvents);
        try {
            eventPublisher.publish(turn, AgentEventType.STEP_STARTED,
                objectAttributes("phase", turn.getPhase()));
            AgentStepResult result = maxStepsReached
                ? maxStepsReached(turn) : stepCore(turn);
            eventPublisher.publish(turn, AgentEventType.STEP_COMPLETED,
                objectAttributes("status", turn.getStatus(),
                    "phase", turn.getPhase(),
                    "toolMessageCount", result == null ? 0 : result.getToolMessages().size()));
            publishDeferredEvents(deferredEvents);
            publishTerminalEvent(turn);
            return result;
        } finally {
            if (parentEvents == null) {
                afterStepEvents.remove();
            } else {
                afterStepEvents.set(parentEvents);
            }
            if (turn.getStatus().isTerminal()) {
                eventPublisher.clearSequence(turn.getId());
            }
        }
    }

    /**
     * 以责任链方式执行 step Middleware，链尾进入内置 ToolCall 状态机。
     *
     * <p>每个 Middleware 可以在调用 next 前后观察或增强步骤，但应保持链只推进一次。</p>
     */
    private AgentStepResult proceedStep(AgentTurn turn, AgentMiddlewareContext context, int index) {
        List<AgentMiddleware> middlewares = turn == null
            ? Collections.<AgentMiddleware>emptyList() : turn.getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
            return executeToolCallingStep(turn);
        }
        AgentMiddleware middleware = middlewares.get(index);
        AgentStepChain chain = next -> proceedStep(turn, next, index + 1);
        return middleware.aroundStep(context, chain);
    }

    /**
     * 执行不包含 step Middleware 包装的通用单步状态机。
     */
    private AgentStepResult stepCore(AgentTurn turn) {
        // Lease 和持久化取消标记必须在任何模型或工具副作用之前检查。
        assertLeaseOwnership(turn);
        refreshCancellation(turn);
        if (turn.isCancellationRequested()) {
            return cancelTurn(turn);
        }
        if (turn.getStatus().isBlocked()) {
            // 阻塞 Turn 只能通过类型化 ResumeCommand 改回可运行状态，step 本身不能越过等待边界。
            return AgentStepResult.of(null, null, null);
        }
        // 时间和累计 Token 预算在每一步入口检查；工具次数还会在具体工具执行前再次检查。
        String budgetReason = budgetExceededReason(turn, false);
        if (budgetReason != null) {
            return budgetExceeded(turn, budgetReason);
        }

        // 已存在的任务计划优先于下一次模型调用推进，避免计划任务与普通对话循环互相竞争。
        AgentStepResult planningResult = planning.advance(turn);
        if (planningResult != null) return planningResult;

        // 规划没有产生独立动作时，再进入 Middleware 和内置 ToolCall 状态机。
        AgentStepResult result = proceedStep(turn,
            new AgentMiddlewareContext(this, turn, turn.getPrompt()), 0);
        if (result == null) {
            return handleFailure(turn, null,
                new IllegalStateException("Agent step returned null result"),
                turn.getPhase());
        }
        // 模型或工具执行期间控制面可能提交取消，返回本步之前再同步一次单调取消信号。
        refreshCancellation(turn);
        if (turn.isCancellationRequested() && !turn.getStatus().isTerminal()) {
            return cancelTurn(turn);
        }
        return result;
    }

    /**
     * 使用模型原生 ToolCall 协议推进一个稳定执行步骤。
     *
     * <p>Phase 是恢复游标而不是业务状态：MODEL 表示下一步应请求模型，TOOLS 表示模型已经生成了
     * 尚未处理完的 ToolCall，FINISHED 表示运行已经结束。</p>
     *
     * <p>MODEL 阶段最多调用模型一次；TOOLS 阶段按顺序处理当前模型回合遗留的全部工具调用，并在
     * 每个结果写入后保存 Snapshot。</p>
     */
    private AgentStepResult executeToolCallingStep(AgentTurn turn) {
        AgentTurnPhase phase = turn.getPhase();
        if (phase == AgentTurnPhase.MODEL) {
            return executeModel(turn);
        }
        if (phase == AgentTurnPhase.TOOLS) {
            return executePendingTools(turn, null);
        }
        if (phase == AgentTurnPhase.FINISHED) {
            return complete(turn, null, lastAiMessage(turn));
        }
        return handleFailure(turn, null,
            new IllegalStateException("Unsupported agent phase: " + phase), phase);
    }

    /**
     * 创建子 Turn，并让父 Turn 等待子任务完成。
     *
     * <p>目标 Agent 使用 loadActive 加载。父等待快照和新子快照通过 TurnStore 的原子接口一起保存，
     * 避免只创建子 Turn 或只暂停父 Turn 的部分状态。</p>
     */
    public AgentTurn startChild(AgentTurn parent, String childAgentId, String input) {
        // 创建子 Turn 会同时改变父 Turn 状态，因此必须仍持有父 Turn 的有效 Lease。
        assertLeaseOwnership(parent);
        // 新子任务使用目标 Agent 当前生效版本；一旦创建，其具体版本会写入子 Snapshot。
        Agent childAgent = agentLoader.loadActive(childAgentId);
        if (childAgent == null) {
            throw new IllegalStateException("Active child agent cannot be loaded: " + childAgentId);
        }
        prepareAgent(childAgent);
        AgentTurn child = AgentTurn.startChild(childAgent, input, parent);
        prepareTurn(child);
        planning.bindChild(parent, child, childAgentId);
        AgentSuspension suspension = AgentSuspension.child(child.getId());
        AgentTurnSnapshot parentSnapshot = parent.toSnapshot();
        AgentTurnSnapshot parentWaiting = parentSnapshot.withState(parentSnapshot.getState().toBuilder()
            .status(AgentTurnStatus.WAITING_FOR_CHILD)
            .phase(suspension.getResumePhase())
            .suspension(suspension)
            .build());
        // 父等待状态与子 READY 状态必须原子提交，避免出现孤儿子任务或永久等待的父任务。
        ParentChildTurnSnapshots saved = turnStore.saveParentAndChild(
            parentWaiting, parent.getVersion(), child.toSnapshot());
        parent.suspend(AgentTurnStatus.WAITING_FOR_CHILD, suspension);
        parent.updateVersion(saved.getParent().getState().getVersion());
        child.updateVersion(saved.getChild().getState().getVersion());
        eventPublisher.notifySnapshotSaved(parent, saved.getParent());
        eventPublisher.notifySnapshotSaved(child, saved.getChild());
        publishAfterStep(() -> eventPublisher.notifyTurnSuspended(parent, suspension));
        eventPublisher.notifyChildStarted(parent, child);
        planning.notifyTaskStarted(parent, child);
        return child;
    }

    /**
     * 将终止子 Turn 的结果交回正在等待它的父 Turn。
     *
     * <p>该方法具备幂等检查：父 Turn 已不再等待当前 childTurnId 时不会重复写入消息。任务结果按父
     * Agent 的 planningPolicy 限制写回长度，子 Turn 中的完整最终输出不会被修改。</p>
     */
    public AgentTurn resumeParentFromChild(AgentTurn child) {
        return planning.resumeParentFromChild(child);
    }

    /**
     * 查询根 Turn 中的计划以及当前子 Turn 的真实阻塞状态。
     */
    public AgentTaskProgress getTaskProgress(String turnId) {
        return planning.getTaskProgress(turnId);
    }

    /**
     * 修复子 Turn 已终止但父 Turn 尚未收到完成信号的状态。
     *
     * <p>该补偿操作是幂等的，适合每次 Worker 轮询前执行。多个 Worker 同时修复时，
     * 父 Turn 的乐观锁只允许一个写入成功，其他竞争者会在后续轮询看到已恢复状态。</p>
     */
    public int recoverCompletedChildren(int limit) {
        int recovered = 0;
        for (AgentTurnSnapshot snapshot : turnStore.findTerminalChildrenWithWaitingParent(limit)) {
            try {
                AgentTurn child = restore(snapshot.getState().getTurnId());
                AgentTurn parent = resumeParentFromChild(child);
                if (parent != null && !parent.getStatus().isBlocked()) recovered++;
            } catch (AgentTurnVersionConflictException ignored) {
                // 另一个 Worker 已经完成相同修复，下一次查询会自然过滤该父 Turn。
            } catch (IllegalStateException error) {
                log.debug("Completed child recovery skipped, childTurnId={}",
                    snapshot.getState().getTurnId(), error);
            }
        }
        return recovered;
    }

    /**
     * 在指定 Worker 的租约上下文中推进已经领取的 Turn。
     */
    AgentTurn runLeased(AgentTurn turn, String workerId, String leaseId) {
        if (!workerId.equals(turn.getLeaseOwner())
            || leaseId == null || !leaseId.equals(turn.getLeaseId())
            || turn.getLeaseUntil() <= turnStore.currentTimeMillis()) {
            throw new IllegalStateException("AgentTurn lease is not active for worker: " + workerId);
        }
        activeWorkerId.set(workerId);
        activeLeaseId.set(leaseId);
        try {
            if (turn.getStatus() == AgentTurnStatus.RETRY_SCHEDULED) {
                return resume(turn, AgentResumeCommand.retry());
            }
            return runUntilBlocked(turn);
        } finally {
            activeWorkerId.remove();
            activeLeaseId.remove();
        }
    }

    /**
     * 执行一个模型回合，并根据响应进入完成状态或 ToolCall 处理阶段。
     *
     * <p>模型响应只有在通过基础校验后才加入 Prompt。若响应包含 ToolCall，必须先保存 pendingToolCalls
     * 和 TOOLS Phase，再执行工具；该顺序保证模型已经作出的工具决定可跨进程恢复。</p>
     */
    private AgentStepResult executeModel(AgentTurn turn) {
        Agent agent = turn.getAgent();
        if (turn.getIterationCount() >= turn.getExecutionPolicy().getMaxIterations()) {
            finalizeInterruptedHistory(turn, "maximum model iterations reached");
            turn.markMaxIterationsReached();
            saveSnapshot(turn);
            return AgentStepResult.of(null, null, null);
        }

        // 迭代次数表示模型调用次数，在发起请求前增加，失败的模型请求同样消耗一次尝试。
        turn.incrementIteration();
        eventPublisher.notifyModelStart(turn);
        AiMessageResponse response;
        try {
            // 模型 Middleware 以责任链包裹最终调用，可用于 tracing、缓存或受控 Prompt 增强。
            AgentMiddlewareContext middlewareContext = new AgentMiddlewareContext(
                this, turn, turn.getPrompt());
            response = proceedModelCall(turn, middlewareContext, 0);
            validateResponse(response);
            eventPublisher.notifyModelEnd(turn, response);
        } catch (RuntimeException error) {
            return handleFailure(turn, null, error, AgentTurnPhase.MODEL);
        }
        refreshCancellation(turn);
        if (turn.isCancellationRequested()) {
            return cancelTurn(turn);
        }

        AiMessage message = response.getMessage();
        // 部分模型供应商不返回 ToolCall ID；在写入 Prompt 和 Snapshot 前补成稳定关联键。
        ensureToolCallIds(turn, message);
        turn.addUsage(message);
        turn.getPrompt().addMessage(message);
        String budgetReason = budgetExceededReason(turn, false);
        if (budgetReason != null) {
            return budgetExceeded(turn, budgetReason);
        }

        if (!message.hasToolCalls()) {
            // 不含 ToolCall 的 AI 消息是内置状态机的最终回答。
            return complete(turn, response, message);
        }

        // 先保存模型决策，再执行可能产生外部副作用的工具。
        turn.setPendingToolCalls(message.getToolCalls());
        turn.moveTo(AgentTurnPhase.TOOLS);
        saveSnapshot(turn);
        return executePendingTools(turn, response);
    }

    /**
     * 递归构造模型 Middleware 责任链，链尾由 AgentModelInvoker 统一调用 ChatModel。
     */
    private AiMessageResponse proceedModelCall(AgentTurn turn, AgentMiddlewareContext context, int index) {
        List<AgentMiddleware> middlewares = turn.getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
            return invokeModel(turn, context.getPrompt());
        }
        AgentMiddleware middleware = middlewares.get(index);
        AgentModelCallChain chain = next -> proceedModelCall(turn, next, index + 1);
        return middleware.aroundModelCall(context, chain);
    }

    /**
     * 调用模型适配器，并由适配器发布细粒度流式事件。
     */
    private AiMessageResponse invokeModel(AgentTurn turn, Prompt prompt) {
        return modelInvoker.invoke(turn, prompt);
    }

    /**
     * 逐个处理待执行 ToolCall，并在每个结果写入后保存 Snapshot。
     */
    private AgentStepResult executePendingTools(AgentTurn turn, AiMessageResponse response) {
        List<ToolMessage> results = new ArrayList<>();
        while (!turn.getPendingToolCalls().isEmpty()) {
            // 每个 ToolCall 前重新检查取消和通用预算；工具次数只约束后面的业务工具。
            if (turn.isCancellationRequested()) {
                return cancelTurn(turn);
            }
            String budgetReason = budgetExceededReason(turn, false);
            if (budgetReason != null) {
                return budgetExceeded(turn, budgetReason);
            }

            // 始终处理队首调用；成功写入 ToolMessage 后才从 pending 列表移除。
            ToolCall call = turn.getPendingToolCalls().get(0);
            if (planning.isPlanningTool(call)) {
                try {
                    // 规划工具只转换 Turn 内部状态，不经过业务工具审批和外部执行器。
                    ToolMessage planned = planning.applyToolCall(turn, call);
                    appendToolResult(turn, call, planned);
                    results.add(planned);
                    planning.notifyPlanChanged(turn, call);
                    continue;
                } catch (RuntimeException error) {
                    return handleFailure(turn, response, error, AgentTurnPhase.TOOLS);
                }
            }
            Tool tool = resolveTool(turn, call);
            if (tool == null) {
                // 恢复后找不到原工具表示 Agent 版本不完整，不能跳过调用继续生成答案。
                return handleFailure(turn, response,
                    new AgentToolNotFoundException(call.getName()), AgentTurnPhase.TOOLS);
            }

            if (AgentUserInputTool.isUserInputTool(tool)) {
                try {
                    AgentFormDefinition form = AgentUserInputTool.resolveForm(tool, call);
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("formKey", form.getFormKey());
                    metadata.put("schema", form.getSchema());
                    metadata.put("toolName", AgentUserInputTool.NAME);
                    Object title = form.getSchema().get("title");
                    String message = title == null
                        ? form.getFormKey() : String.valueOf(title);
                    AgentSuspension suspension = AgentSuspension.userInput(
                        callKey(call), message, metadata);
                    suspend(turn, suspension);
                    return AgentStepResult.of(response, results, null);
                } catch (RuntimeException error) {
                    return handleFailure(turn, response, error, AgentTurnPhase.TOOLS);
                }
            }

            // 内置控制工具不计入业务工具调用次数，真实工具执行前才检查 maxToolCalls。
            budgetReason = budgetExceededReason(turn, true);
            if (budgetReason != null) {
                return budgetExceeded(turn, budgetReason);
            }

            // 已恢复的审批决定优先；首次遇到 ToolCall 时才执行动态审批策略。
            Boolean approval = turn.getToolApproval(callKey(call));
            ToolApprovalDecision decision = approval == null
                ? turn.getAgent().getToolApprovalPolicy().decide(turn, call, tool)
                : (approval ? ToolApprovalDecision.ALLOW : ToolApprovalDecision.DENY);
            if (decision == null) {
                return handleFailure(turn, response,
                    new IllegalStateException("ToolApprovalPolicy returned null"), AgentTurnPhase.TOOLS);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.REQUIRE_APPROVAL) {
                // Suspension 保存当前 ToolCall 关联 ID 和 TOOLS 恢复点，审批后不会重新调用模型。
                AgentSuspension suspension = AgentSuspension.toolApproval(
                    callKey(call), call.getName(), decision);
                suspend(turn, suspension);
                eventPublisher.notifyToolApprovalRequested(turn, call, decision);
                return AgentStepResult.of(response, results, null);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.DENY) {
                // 拒绝不是运行时异常，而是结构化 ToolMessage；模型可据此向用户解释或选择替代方案。
                ToolMessage rejected = buildToolRejectedMessage(turn, call, decision);
                appendToolResult(turn, call, rejected);
                results.add(rejected);
                continue;
            }

            eventPublisher.notifyToolStart(turn, call);
            ToolMessage completedResult;
            try {
                turn.incrementToolCallCount();
                completedResult = executeTool(turn, tool, call);
            } catch (AgentFormRequiredException request) {
                if (!turn.getToolInputData(callKey(call)).isEmpty()) {
                    IllegalStateException error = new IllegalStateException(
                        "tool requested user input again after resuming: " + call.getName(), request);
                    eventPublisher.notifyToolError(turn, call, error);
                    return handleFailure(turn, response, error, AgentTurnPhase.TOOLS);
                }
                // 输入请求发生在副作用之前，恢复后仍是同一个逻辑 ToolCall，不能提前耗尽调用预算。
                turn.rollbackToolCallCount();
                AgentFormDefinition form = request.getForm();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("formKey", form.getFormKey());
                metadata.put("schema", form.getSchema());
                metadata.put("toolName", call.getName());
                metadata.put(INPUT_TARGET_METADATA, TOOL_INPUT_TARGET);
                AgentSuspension suspension = AgentSuspension.userInput(
                    callKey(call), request.getMessage(), metadata);
                suspend(turn, suspension);
                eventPublisher.notifyToolInputRequested(turn, call, form);
                return AgentStepResult.of(response, results, null);
            } catch (RuntimeException error) {
                eventPublisher.notifyToolError(turn, call, error);
                if (turn.getExecutionPolicy().getToolErrorStrategy()
                    == ToolErrorStrategy.RETURN_ERROR_TO_MODEL) {
                    // 将错误交给模型时仍生成与原 ToolCall 匹配的 ToolMessage，保持协议完整。
                    completedResult = buildToolErrorMessage(call, error);
                } else {
                    return handleFailure(turn, response, error, AgentTurnPhase.TOOLS);
                }
            }
            // Snapshot 异常必须直接交给调用方，不能被误判为工具执行异常。
            appendToolResult(turn, call, completedResult);
            results.add(completedResult);
            eventPublisher.notifyToolEnd(turn, call);
            refreshCancellation(turn);
        }

        // 当前模型回合的全部工具调用已处理，下一 step 应让模型读取 ToolMessage 并继续判断。
        turn.moveTo(AgentTurnPhase.MODEL);
        saveSnapshot(turn);
        return AgentStepResult.of(response, results, null);
    }

    /**
     * 原子语义上提交一个工具结果：追加消息、移除 pending 调用并保存 Snapshot。
     *
     * <p>只有 Snapshot 成功后调用才算被运行时确认；具备外部副作用的 Tool 仍应使用
     * {@link AgentToolContext} 中的稳定调用 ID 在业务侧实现幂等。</p>
     */
    private void appendToolResult(AgentTurn turn, ToolCall call, ToolMessage result) {
        turn.getPrompt().addMessage(result);
        turn.removeFirstPendingToolCall();
        saveSnapshot(turn);
    }

    /**
     * 从恢复出的当前 Agent 定义中按名称解析工具；工具对象本身不保存在 Snapshot。
     */
    private Tool resolveTool(AgentTurn turn, ToolCall call) {
        return call == null ? null : turn.getAgent().getTool(call.getName());
    }

    /**
     * 将模型或工具异常统一转换为取消、持久化重试或最终失败状态。
     *
     * <p>安排重试时保存发生异常的 Phase，使 Worker 到期恢复后从原模型或工具边界继续。方法只计算
     * {@code nextRunnableAt} 并返回阻塞结果，不在当前线程 sleep。</p>
     */
    private AgentStepResult handleFailure(AgentTurn turn, AiMessageResponse response,
                                          RuntimeException error, AgentTurnPhase resumePhase) {
        if (error instanceof AgentTurnCancelledException || turn.isCancellationRequested()) {
            return cancelTurn(turn);
        }
        AgentRetryPolicy retry = turn.getExecutionPolicy().getRetryPolicy();
        if (isRetryable(error) && turn.getRetryCount() < retry.getMaxRetries()) {
            // retryCount + 1 表示即将安排的重试序号，用于计算指数退避延迟。
            long runAt = System.currentTimeMillis() + retry.delayMillis(turn.getRetryCount() + 1);
            turn.scheduleRetry(error, resumePhase, runAt);
            saveSnapshot(turn);
            publishAfterStep(() -> eventPublisher.notifyTurnSuspended(
                turn, turn.getSuspension()));
            publishAfterStep(() -> eventPublisher.notifyRetryScheduled(turn, error));
            return AgentStepResult.of(response, null, error);
        }
        finalizeInterruptedHistory(turn, "turn failed: "
            + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        turn.markFailed(error);
        saveSnapshot(turn);
        return AgentStepResult.of(response, null, error);
    }

    /**
     * 参数错误和缺失工具属于确定性配置问题，重复执行不会自行恢复。
     */
    private boolean isRetryable(RuntimeException error) {
        return !(error instanceof AgentToolNotFoundException)
            && !(error instanceof IllegalArgumentException);
    }

    /**
     * 保存最终消息、收束计划状态，并将 Turn 转换为不可再次推进的 COMPLETED 状态。
     */
    AgentStepResult complete(AgentTurn turn, AiMessageResponse response, AiMessage message) {
        planning.finishPlan(turn);
        turn.markCompleted(message == null ? new AiMessage("") : message);
        saveSnapshot(turn);
        return AgentStepResult.of(response, null, null);
    }

    /**
     * 在安全边界响应单调取消信号并保存最终 CANCELLED 状态。
     */
    private AgentStepResult cancelTurn(AgentTurn turn) {
        finalizeInterruptedHistory(turn, "turn cancelled by caller");
        turn.markCancelled();
        saveSnapshot(turn);
        return AgentStepResult.of(null, null, null);
    }

    /**
     * 收束异常终止的模型消息协议，避免下一轮从 ChatMemory 读取未闭合 ToolCall。
     *
     * <p>重试状态不进入此方法；只有已经不能继续推进的 Turn 才追加缺失 ToolMessage 和终止说明。</p>
     */
    private void finalizeInterruptedHistory(AgentTurn turn, String reason) {
        if (Boolean.TRUE.equals(turn.getMetadata().get("agentsflex.interruptedHistoryFinalized"))) {
            return;
        }
        List<ToolCall> pending = turn.getPendingToolCalls();
        for (ToolCall call : pending) {
            ToolMessage result = new ToolMessage();
            result.setToolCallId(call.getId());
            result.setContent("Tool call was not completed: " + reason);
            turn.getPrompt().addMessage(result);
        }
        turn.clearPendingToolCalls();
        turn.getPrompt().addMessage(new AiMessage("The previous AgentTurn ended before completion: " + reason));
        turn.putMetadata("agentsflex.interruptedHistoryFinalized", true);
    }

    /**
     * 保存预算终止原因，避免调用方只能从通用失败信息推断成本限制。
     */
    private AgentStepResult budgetExceeded(AgentTurn turn, String reason) {
        finalizeInterruptedHistory(turn, "execution budget exceeded: " + reason);
        turn.markBudgetExceeded(reason);
        saveSnapshot(turn);
        return AgentStepResult.of(null, null, null);
    }

    /**
     * 保存达到 maxSteps 的终止状态；对应事件由 Step 外层统一发布。
     */
    private AgentStepResult maxStepsReached(AgentTurn turn) {
        finalizeInterruptedHistory(turn, "maximum runner steps reached");
        turn.markMaxStepsReached();
        saveSnapshot(turn);
        return AgentStepResult.of(null, null, null);
    }

    /**
     * 在当前 Step 结束后发布事件；不处于 Step 调用链时立即发布。
     */
    private void publishAfterStep(Runnable event) {
        List<Runnable> events = afterStepEvents.get();
        if (events == null) {
            event.run();
        } else {
            events.add(event);
        }
    }

    private void publishDeferredEvents(List<Runnable> events) {
        for (Runnable event : events) {
            event.run();
        }
    }

    /**
     * 在终止 Step 的 STEP_COMPLETED 之后发布唯一的 Turn 终止事件。
     */
    private void publishTerminalEvent(AgentTurn turn) {
        switch (turn.getStatus()) {
            case COMPLETED:
                eventPublisher.notifyTurnComplete(turn);
                break;
            case FAILED:
                eventPublisher.notifyTurnFailed(turn, turn.getError());
                break;
            case CANCELLED:
                eventPublisher.notifyTurnCancelled(turn);
                break;
            case MAX_ITERATIONS_REACHED:
                eventPublisher.notifyMaxIterationsReached(turn);
                break;
            case MAX_STEPS_REACHED:
                eventPublisher.notifyMaxStepsReached(turn);
                break;
            case BUDGET_EXCEEDED:
                eventPublisher.notifyBudgetExceeded(turn, turn.getBudgetExceededReason());
                break;
            default:
                // 非终止状态没有对应的 Turn 结束事件。
                break;
        }
    }

    /**
     * 返回第一个已经超过的预算维度；未超过时返回 null。
     *
     * @param beforeTool 是否即将执行一个新工具；工具次数只在该边界检查，避免已完成一次调用后被误判
     */
    private String budgetExceededReason(AgentTurn turn, boolean beforeTool) {
        AgentBudget budget = turn.getExecutionPolicy().getBudget();
        long elapsed = System.currentTimeMillis() - turn.getCreatedAt();
        if (budget.getMaxDurationMillis() > 0 && elapsed >= budget.getMaxDurationMillis()) {
            return "maxDurationMillis";
        }
        if (budget.getMaxInputTokens() > 0 && turn.getInputTokens() > budget.getMaxInputTokens()) {
            return "maxInputTokens";
        }
        if (budget.getMaxOutputTokens() > 0 && turn.getOutputTokens() > budget.getMaxOutputTokens()) {
            return "maxOutputTokens";
        }
        if (budget.getMaxTotalTokens() > 0 && turn.getTotalTokens() > budget.getMaxTotalTokens()) {
            return "maxTotalTokens";
        }
        if (beforeTool && budget.getMaxToolCalls() > 0
            && turn.getToolCallCount() >= budget.getMaxToolCalls()) {
            return "maxToolCalls";
        }
        return null;
    }

    private void validateStep(AgentTurn turn) {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        if (turn.getStatus().isTerminal()) {
            throw new IllegalStateException("turn is already terminal: " + turn.getStatus());
        }
    }

    private void validateResponse(AiMessageResponse response) {
        if (response == null) {
            throw new IllegalStateException("chat model returned null response");
        }
        if (response.isError()) {
            throw new IllegalStateException("chat model returned an error: " + response.getErrorMessage());
        }
        if (response.getMessage() == null) {
            throw new IllegalStateException("chat model returned no message");
        }
    }

    /**
     * 执行单个业务工具并把任意 Java 返回值规范化为 ToolMessage。
     *
     * <p>{@link AgentToolContext} 提供跨恢复稳定的调用身份、进度上报和动态取消检查。调用顺序为
     * Agent Middleware、ToolInterceptor、Tool 函数；受控上下文通过 Core ToolContext 传入，
     * 不写入模型可见的工具参数 Schema。</p>
     */
    private ToolMessage executeTool(AgentTurn turn, Tool tool, ToolCall call) {
        List<ToolInterceptor> interceptors = turn.getAgent().getToolInterceptors();

        AgentToolProgressEmitter progressEmitter = (message, data) ->
            eventPublisher.notifyToolProgress(
                turn, call, tool.getName(), message, data);

        AgentToolContext toolContext = new AgentToolContext(
            turn.getId(), turn.getRootTurnId(), turn.getParentTurnId(),
            turn.getAgent().getId(), turn.getAgent().getVersion(), tool, call,
            callKey(call), progressEmitter, turn::isCancellationRequested,
            turn.getToolInputData(callKey(call)));

        AgentMiddlewareContext middlewareContext =
            AgentMiddlewareContext.forToolCall(this, turn, toolContext);
        Object value = proceedToolCall(middlewareContext, 0, interceptors);
        // ToolMessage 内容必须是字符串：标量直接转换，结构化对象统一序列化为 JSON。
        ToolMessage result = new ToolMessage();
        result.setToolCallId(callKey(call));
        if (value == null) {
            result.setContent("null");
        } else if (value instanceof CharSequence || value instanceof Number
            || value instanceof Boolean) {
            result.setContent(value.toString());
        } else {
            result.setContent(JSON.toJSONString(value));
        }
        return result;
    }

    /**
     * 递归构造 Agent 工具 Middleware 链，链尾再交给核心 ToolExecutor 和 ToolInterceptor。
     */
    private Object proceedToolCall(AgentMiddlewareContext context, int index,
                                   List<ToolInterceptor> interceptors) {
        List<AgentMiddleware> middlewares = context.getRun().getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
            // 受控上下文只在本次 JVM 调用链存在，不会混入模型可见的工具参数。
            AgentToolContext toolContext = context.getToolContext();
            if (toolContext == null) {
                throw new IllegalArgumentException(
                    "toolContext must not be null in the tool middleware chain");
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put(AgentToolContext.CONTEXT_ATTRIBUTE, toolContext);
            return new ToolExecutor(toolContext.getTool(), toolContext.getToolCall(), interceptors)
                .execute(attributes);
        }
        AgentMiddleware middleware = middlewares.get(index);
        AgentToolCallChain chain = next -> proceedToolCall(next, index + 1, interceptors);
        return middleware.aroundToolCall(context, chain);
    }

    /**
     * 把允许交回模型处理的工具异常编码为与原 ToolCall 关联的结构化错误消息。
     */
    private ToolMessage buildToolErrorMessage(ToolCall call, Throwable error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("type", error instanceof AgentToolNotFoundException
            ? "tool_not_found" : "tool_execution_error");
        body.put("message", error.getMessage());
        ToolMessage result = new ToolMessage();
        result.setToolCallId(callKey(call));
        result.setContent(JSON.toJSONString(body));
        return result;
    }

    /**
     * 合并审批策略与人工恢复命令中的拒绝信息，生成模型可理解的结构化结果。
     */
    private ToolMessage buildToolRejectedMessage(AgentTurn turn, ToolCall call,
                                                 ToolApprovalDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("type", "tool_rejected");
        Object resumedReason = turn.getMetadata().get("toolRejectionReason." + callKey(call));
        Object approvalAudit = turn.getMetadata().get("toolApprovalAudit." + callKey(call));
        @SuppressWarnings("unchecked")
        Map<String, Object> approvalValues = approvalAudit instanceof Map
            ? (Map<String, Object>) approvalAudit : Collections.emptyMap();
        String policyReason = StringUtil.hasText(decision.getMessage())
            ? decision.getMessage() : decision.getReason();
        body.put("code", StringUtil.hasText(decision.getCode())
            ? decision.getCode() : approvalValues.get("approvalCode"));
        body.put("message", resumedReason != null ? resumedReason
            : (StringUtil.hasText(policyReason) ? policyReason
            : (approvalValues.get("approvalReason") == null
            ? "Tool execution was rejected" : approvalValues.get("approvalReason"))));
        body.put("metadata", decision.getMetadata().isEmpty()
            ? approvalValues : decision.getMetadata());
        if (approvalAudit != null) body.put("approval", approvalAudit);
        ToolMessage result = new ToolMessage();
        result.setToolCallId(callKey(call));
        result.setContent(JSON.toJSONString(body));
        return result;
    }

    /**
     * 返回 ToolCall 的稳定关联键；旧供应商缺少 ID 时兼容使用工具名。
     */
    private String callKey(ToolCall call) {
        return StringUtil.hasText(call.getId()) ? call.getId() : call.getName();
    }

    /**
     * 为未提供 ID 的 ToolCall 生成可持久化且在当前 Turn 内唯一的关联 ID。
     */
    private void ensureToolCallIds(AgentTurn turn, AiMessage message) {
        if (message == null || !message.hasToolCalls()) {
            return;
        }
        int index = 0;
        for (ToolCall call : message.getToolCalls()) {
            if (!StringUtil.hasText(call.getId())) {
                call.setId(turn.getId() + "-" + turn.getIterationCount() + "-" + index);
            }
            index++;
        }
    }

    /**
     * 从消息历史倒序查找最近的 AI 消息，供 FINISHED Phase 完成运行。
     */
    private AiMessage lastAiMessage(AgentTurn turn) {
        List<Message> messages = turn.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage) {
                return (AiMessage) messages.get(i);
            }
        }
        return null;
    }

    /**
     * 确保 Turn 已装配规划工具，并兼容尚未保存初始 Snapshot 的包内创建路径。
     */
    private void ensurePreparedAndSnapshotSaved(AgentTurn turn) {
        prepareTurn(turn);
        if (turn.getVersion() < 0) {
            saveSnapshot(turn);
        }
    }

    /**
     * 解析规划白名单中的完整 Agent，并为当前 Turn 装配模型可见的规划工具。
     */
    private void prepareTurn(AgentTurn turn) {
        if (turn == null) throw new IllegalArgumentException("turn must not be null");
        prepareAgent(turn.getAgent());
        planning.prepareTools(turn);
    }

    /**
     * 拒绝非租约持有者推进仍处于有效租约中的 Turn。
     *
     * <p>Worker 路径同时校验 owner、唯一 leaseId 和存储端到期时间；同步 API 路径只能推进当前没有
     * 有效 Lease 的 Turn。该检查必须位于每个副作用和 Snapshot 之前，防止过期 Worker 覆盖新状态。</p>
     */
    private void assertLeaseOwnership(AgentTurn turn) {
        String workerId = activeWorkerId.get();
        if (workerId != null) {
            if (!workerId.equals(turn.getLeaseOwner())
                || !StringUtil.hasText(activeLeaseId.get())
                || !activeLeaseId.get().equals(turn.getLeaseId())
                || turn.getLeaseUntil() <= turnStore.currentTimeMillis()) {
                throw new IllegalStateException("AgentTurn lease was lost by worker: " + workerId);
            }
            return;
        }
        if (StringUtil.hasText(turn.getLeaseOwner())
            && turn.getLeaseUntil() > turnStore.currentTimeMillis()) {
            throw new IllegalStateException("AgentTurn is leased by worker: " + turn.getLeaseOwner());
        }
    }

    private void prepareAgent(Agent agent) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
    }

    private Agent loadActiveAgent(String agentId) {
        if (!StringUtil.hasText(agentId)) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        Agent agent = agentLoader.loadActive(agentId);
        if (agent == null) {
            throw new IllegalStateException("Active Agent cannot be loaded: " + agentId);
        }
        if (!agentId.equals(agent.getId())) {
            throw new IllegalStateException("AgentLoader returned mismatched Agent: expected="
                + agentId + ", actual=" + agent.getId());
        }
        return agent;
    }

    /**
     * 将具体暂停原因映射为对外可查询的生命周期等待状态。
     */
    private AgentTurnStatus blockedStatusFor(AgentSuspensionType type) {
        switch (type) {
            case USER_INPUT:
                return AgentTurnStatus.WAITING_FOR_USER;
            case TOOL_APPROVAL:
                return AgentTurnStatus.WAITING_FOR_APPROVAL;
            case CHILD_AGENT:
                return AgentTurnStatus.WAITING_FOR_CHILD;
            case RETRY:
                return AgentTurnStatus.RETRY_SCHEDULED;
            default:
                throw new IllegalStateException("Unsupported suspension type: " + type);
        }
    }

    /**
     * 校验恢复命令与当前 Suspension 匹配，并把命令携带的数据写入 Turn。
     *
     * <p>本方法只应用数据，不改变 Status 和 Phase；调用方在校验完成后统一调用 resumeAt 并保存
     * Snapshot，避免部分应用一个无效命令。</p>
     */
    private void applyResumeCommand(AgentTurn turn, AgentSuspension suspension,
                                    AgentResumeCommand command) {
        if (suspension == null) {
            throw new IllegalStateException("blocked turn has no suspension data");
        }
        switch (suspension.getType()) {
            case USER_INPUT:
                applyUserInput(turn, suspension, command);
                break;
            case TOOL_APPROVAL:
                applyToolApproval(turn, suspension, command);
                break;
            case RETRY:
                if (command.getType() != AgentResumeCommandType.CONTINUE
                    && command.getType() != AgentResumeCommandType.RETRY) {
                    throw new IllegalArgumentException("CONTINUE or RETRY command is required");
                }
                if (turn.getNextRunnableAt() > System.currentTimeMillis()
                    && command.getType() == AgentResumeCommandType.RETRY) {
                    throw new IllegalStateException("retry is not due yet: " + turn.getNextRunnableAt());
                }
                // RETRY 遵守 nextRunnableAt；CONTINUE 是显式人工强制继续，可忽略尚未到期的调度时间。
                turn.clearRetryError();
                break;
            case CHILD_AGENT:
                requireCommand(command, AgentResumeCommandType.CHILD_COMPLETED);
                requireCorrelation(suspension, command);
                break;
            default:
                throw new IllegalStateException("Unsupported suspension type: " + suspension.getType());
        }
        turn.putMetadata("lastResumeCommand", command.getType().name());
        turn.putMetadata("lastResumeCorrelationId", command.getCorrelationId());
        if (!command.getMetadata().isEmpty()) {
            turn.putMetadata("lastResumeCommandMetadata",
                new LinkedHashMap<String, Object>(command.getMetadata()));
        }
    }

    /**
     * 应用纯文本补充或 request_user_input 工具产生的结构化表单结果。
     */
    private void applyUserInput(AgentTurn turn, AgentSuspension suspension,
                                AgentResumeCommand command) {
        requireCommand(command, AgentResumeCommandType.USER_INPUT);
        boolean hasContent = StringUtil.hasText(command.getContent());
        boolean hasData = !command.getData().isEmpty();
        if (!hasContent && !hasData) {
            throw new IllegalArgumentException("user input content or data must not be empty");
        }

        // 旧的手工 Suspension 没有关联 ToolCall，继续保留追加 UserMessage 的兼容语义。
        if (!StringUtil.hasText(suspension.getCorrelationId())) {
            turn.getPrompt().addUserMessage(hasContent
                ? command.getContent() : JSON.toJSONString(command.getData()));
            return;
        }

        requireCorrelation(suspension, command);
        List<ToolCall> pending = turn.getPendingToolCalls();
        if (pending.isEmpty()) {
            throw new IllegalStateException("user input suspension has no pending ToolCall");
        }
        ToolCall call = pending.get(0);
        if (TOOL_INPUT_TARGET.equals(
            String.valueOf(suspension.getMetadata().get(INPUT_TARGET_METADATA)))) {
            if (!hasData) {
                throw new IllegalArgumentException(
                    "structured data is required for a suspended business tool");
            }
            if (!suspension.getCorrelationId().equals(callKey(call))
                || !call.getName().equals(suspension.getMetadata().get("toolName"))) {
                throw new IllegalStateException(
                    "user input suspension does not match the pending business ToolCall");
            }
            turn.putToolInputData(callKey(call), command.getData());
            return;
        }
        if (!suspension.getCorrelationId().equals(callKey(call))
            || !AgentUserInputTool.NAME.equals(call.getName())) {
            throw new IllegalStateException(
                "user input suspension does not match the pending ToolCall");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "submitted");
        body.put("formKey", suspension.getMetadata().get("formKey"));
        if (hasData) body.put("data", command.getData());
        if (hasContent) body.put("content", command.getContent());
        ToolMessage result = new ToolMessage();
        result.setToolCallId(callKey(call));
        result.setContent(JSON.toJSONString(body));
        turn.getPrompt().addMessage(result);
        turn.removeFirstPendingToolCall();
    }

    /**
     * 校验审批命令及 ToolCall 关联 ID，并记录后续工具阶段可直接读取的布尔决定。
     */
    private void applyToolApproval(AgentTurn turn, AgentSuspension suspension,
                                   AgentResumeCommand command) {
        if (command.getType() != AgentResumeCommandType.APPROVE_TOOL
            && command.getType() != AgentResumeCommandType.REJECT_TOOL) {
            throw new IllegalArgumentException("APPROVE_TOOL or REJECT_TOOL command is required");
        }
        requireCorrelation(suspension, command);
        turn.approveTool(suspension.getCorrelationId(),
            command.getType() == AgentResumeCommandType.APPROVE_TOOL);
        turn.putMetadata("toolApprovalAudit." + suspension.getCorrelationId(),
            new LinkedHashMap<String, Object>(suspension.getMetadata()));
        if (command.getType() == AgentResumeCommandType.REJECT_TOOL
            && StringUtil.hasText(command.getContent())) {
            turn.putMetadata("toolRejectionReason." + suspension.getCorrelationId(), command.getContent());
        }
    }

    private void requireCommand(AgentResumeCommand command, AgentResumeCommandType type) {
        if (command.getType() != type) {
            throw new IllegalArgumentException(type + " command is required");
        }
    }

    private void requireCorrelation(AgentSuspension suspension, AgentResumeCommand command) {
        if (!suspension.getCorrelationId().equals(command.getCorrelationId())) {
            throw new IllegalArgumentException("resume command correlationId does not match suspension");
        }
    }

    /**
     * 创建结构化数据使用的键值映射，忽略空键或空值。
     */
    private Map<String, Object> objectAttributes(Object... values) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i] != null && values[i + 1] != null) {
                attributes.put(String.valueOf(values[i]), values[i + 1]);
            }
        }
        return attributes;
    }

    /**
     * 将 Store 中的单调取消信号同步到当前内存 Turn。
     */
    private void refreshCancellation(AgentTurn turn) {
        if (!turn.isCancellationRequested()
            && turnStore.isCancellationRequested(turn.getId())) {
            turn.requestCancellation();
        }
    }

    private static final class AgentTurnCancelledException extends RuntimeException {
    }

    /**
     * 表示恢复执行时，当前 Agent 已无法提供快照所记录的工具。
     */
    private static final class AgentToolNotFoundException extends RuntimeException {
        private AgentToolNotFoundException(String name) {
            super("tool not found: " + name);
        }
    }

}

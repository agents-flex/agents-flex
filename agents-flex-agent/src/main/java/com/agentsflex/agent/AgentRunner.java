/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.compression.AgentContextCompressionPolicy;
import com.agentsflex.agent.compression.AgentContextCompressionResult;
import com.agentsflex.agent.compression.AgentContextCompressor;
import com.agentsflex.agent.event.AgentEventListener;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.exception.AgentConversationBusyException;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;
import com.agentsflex.agent.loader.AgentLoader;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentModelCallChain;
import com.agentsflex.agent.middleware.AgentStepChain;
import com.agentsflex.agent.store.AgentTurnStore;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.*;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.ChatMemoryProvider;
import com.agentsflex.core.message.*;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutionTarget;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 创建、推进、暂停和恢复 {@link AgentTurn} 的核心执行器。
 *
 * <p>Runner 可以理解为一个可持久化的状态机执行器。{@link Agent} 提供模型、指令、工具和执行
 * 策略，{@link AgentTurn} 保存某个 Agent 一次输入到最终结果的可变状态，Runner 根据 Turn 的
 * {@link AgentTurnStatus} 和 {@link AgentTurnExecutionPoint} 决定下一步调用模型、执行工具、等待外部事件，
 * 或结束本轮。每个 Turn 对应一次独立的 Agent 调用。</p>
 *
 * <p>一次标准执行由三层组成：</p>
 * <ol>
 *     <li>{@link #runUntilBlocked(AgentTurn)} 决定是否继续循环；</li>
 *     <li>{@link #step(AgentTurn)} 完成取消、Lease、预算和 Middleware 等通用检查；</li>
 *     <li>内置 ToolCall 状态机根据当前 ExecutionPoint 推进模型调用或工具执行。</li>
 * </ol>
 *
 * <p>内置状态机使用模型原生 ToolCall。模型产生 ToolCall 后，Runner 先把调用及参数保存为
 * Snapshot，再逐个完成审批、工具执行和 ToolMessage 写入。审批恢复时因此可以继续执行已经确认的
 * 原始 ToolCall，而不需要重新请求模型生成参数。</p>
 *
 * <p>Runner 同时负责预算检查、自动重试、暂停恢复和生命周期事件。
 * 所有需要跨进程恢复的状态最终通过 {@link AgentTurnStore} 持久化；Runner 自身不长期保存任务状态，
 * 因而通常作为应用级对象复用。</p>
 *
 * <p>直接调用 {@code run(...)} 会在当前线程推进 Turn；分布式长任务应先调用 {@code start(...)}
 * 保存 READY Snapshot，再由 AgentWorker 通过租约领取。不要让两个线程直接推进同一个
 * AgentTurn 对象。</p>
 */
public final class AgentRunner {

    static final String TOOL_INPUT_TARGET = "TOOL";

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
     * 统一模型调用、Token 统计和事件发布的适配器。
     */
    private final AgentModelInvoker modelInvoker;
    /**
     * 推进模型产生的 ToolCall，并把执行结果提交回 Runner 的生命周期边界。
     */
    private final AgentToolCallProcessor toolCallProcessor;
    /**
     * 保存当前 JVM 内可中断的模型或工具执行句柄。
     */
    private final AgentExecutionRegistry executionRegistry;
    private final AgentRunnerOptions runnerOptions;
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
    /**
     * 同一 Runner 内按 conversationId 串行创建初始 Turn，避免检查与保存之间出现竞态。
     */
    private final ConcurrentMap<String, Object> conversationLocks = new ConcurrentHashMap<>();
    /**
     * 同一 Turn 内即时压缩结果缓存；key 包含输入消息 ID，历史变化时自动失效。
     */
    private final ConcurrentMap<String, List<Message>> compressionCache = new ConcurrentHashMap<>();

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
        this(turnStore, agentLoader, null, AgentRunnerOptions.defaults());
    }

    /**
     * 创建 Runner 并组装事件、模型调用与可选 ChatMemory 投影组件。
     *
     * @param turnStore          Snapshot、CAS 和租约存储
     * @param agentLoader        Agent 版本加载器
     * @param chatMemoryProvider 可选业务会话存储 Provider
     */
    private AgentRunner(AgentTurnStore turnStore, AgentLoader agentLoader,
                        ChatMemoryProvider chatMemoryProvider, AgentRunnerOptions runnerOptions) {
        if (turnStore == null || agentLoader == null) {
            throw new IllegalArgumentException(
                "AgentRunner dependencies must not be null");
        }
        this.turnStore = turnStore;
        this.agentLoader = agentLoader;
        this.runnerOptions = runnerOptions == null ? AgentRunnerOptions.defaults() : runnerOptions;
        this.eventPublisher = new AgentEventPublisher(this.runnerOptions);
        this.executionRegistry = new AgentExecutionRegistry();
        this.modelInvoker = new AgentModelInvoker(eventPublisher, this.runnerOptions.getModelExecutor(),
            executionRegistry);
        this.chatMemory = new AgentRunnerChatMemory(chatMemoryProvider);
        this.toolCallProcessor = new AgentToolCallProcessor(this, eventPublisher, this.runnerOptions);
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
        private AgentRunnerOptions runnerOptions = AgentRunnerOptions.defaults();

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
         * 设置事件分发、工具执行和事件脱敏等基础设施配置。
         */
        public Builder options(AgentRunnerOptions value) {
            runnerOptions = value == null ? AgentRunnerOptions.defaults() : value;
            return this;
        }

        /**
         * 校验全部依赖并创建 Runner。
         */
        public AgentRunner build() {
            return new AgentRunner(turnStore, agentLoader, chatMemoryProvider, runnerOptions);
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
     * 重试时间。</p>
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
        return runUntilBlocked(submitMessage(agentId, conversationId, userMessage, options));
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
        return runUntilBlocked(submitMessage(agent, conversationId, userMessage, options));
    }

    /**
     * 向业务会话提交消息，但不在当前线程继续执行。
     *
     * <p>会话没有活跃 Turn 时创建 READY Turn；已有阻塞 Turn 时把消息追加到该 Turn，闭合仍待处理的
     * ToolCall，并保存为 RUNNING。正在运行的 Turn 仍会抛出 AgentConversationBusyException。</p>
     */
    public AgentTurn submitMessage(String agentId, String conversationId,
                                   UserMessage userMessage) {
        return submitMessage(agentId, conversationId, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 使用纯文本向业务会话提交消息，但不在当前线程继续执行。
     */
    public AgentTurn submitMessage(String agentId, String conversationId,
                                   String userInput) {
        return submitMessage(agentId, conversationId, new UserMessage(userInput));
    }

    /**
     * 向业务会话提交消息；options 只在需要创建新 Turn 时生效。
     */
    public AgentTurn submitMessage(String agentId, String conversationId,
                                   UserMessage userMessage, AgentTurnOptions options) {
        return submitConversationMessage(agentId, null, conversationId, userMessage, options);
    }

    /**
     * 使用给定 Agent 向业务会话提交消息，但不在当前线程继续执行。
     */
    public AgentTurn submitMessage(Agent agent, String conversationId,
                                   UserMessage userMessage) {
        return submitMessage(agent, conversationId, userMessage, AgentTurnOptions.defaults());
    }

    /**
     * 使用纯文本和给定 Agent 向业务会话提交消息，但不在当前线程继续执行。
     */
    public AgentTurn submitMessage(Agent agent, String conversationId,
                                   String userInput) {
        return submitMessage(agent, conversationId, new UserMessage(userInput));
    }

    /**
     * 使用给定 Agent 向业务会话提交消息；options 只在需要创建新 Turn 时生效。
     */
    public AgentTurn submitMessage(Agent agent, String conversationId,
                                   UserMessage userMessage, AgentTurnOptions options) {
        if (agent == null) throw new IllegalArgumentException("agent must not be null");
        return submitConversationMessage(agent.getId(), agent, conversationId,
            userMessage, options);
    }

    /**
     * 原子地选择“创建新 Turn”或“向阻塞 Turn 追加消息”。
     *
     * <p>同一 Runner 使用 conversation lock 缩小竞争窗口；跨 Runner/进程竞争最终由 TurnStore 的
     * active-conversation 约束和 Snapshot 乐观锁裁决。恢复状态会在下一次模型副作用之前先保存。</p>
     */
    private AgentTurn submitConversationMessage(String agentId, Agent requestedAgent,
                                                String conversationId,
                                                UserMessage userMessage,
                                                AgentTurnOptions options) {
        if (!chatMemory.isEnabled()) {
            throw new IllegalStateException(
                "ChatMemoryProvider must be configured for conversation APIs");
        }
        if (!StringUtil.hasText(agentId) || !StringUtil.hasText(conversationId)
            || userMessage == null || options == null) {
            throw new IllegalArgumentException(
                "agentId, conversationId, userMessage and options must not be empty");
        }
        Object lock = conversationLocks.computeIfAbsent(conversationId, key -> new Object());
        synchronized (lock) {
            AgentTurnSnapshot active = turnStore.findActiveTurn(conversationId);
            if (active == null) {
                return requestedAgent == null
                    ? start(agentId, conversationId, userMessage, options)
                    : start(requestedAgent, conversationId, userMessage, options);
            }
            if (!agentId.equals(active.getAgentId())) {
                throw new AgentConversationBusyException(conversationId,
                    active.getState().getTurnId(), active.getState().getStatus());
            }
            AgentTurn turn = restore(active.getState().getTurnId());
            // HTTP/消息队列重投同一个 UserMessage 时直接返回当前事实状态。检查必须早于 blocked 校验，
            // 因为第一次提交已经可能把 Turn 保存成 RUNNING，重复请求不应因此变成“会话忙”。
            if (turn.hasProcessedUserMessage(userMessage.getMessageId())) {
                return turn;
            }
            if (!turn.getStatus().isBlocked()) {
                throw new AgentConversationBusyException(conversationId,
                    turn.getId(), turn.getStatus());
            }
            try {
                return submitResume(turn, AgentResumeCommand.userMessage(userMessage));
            } catch (AgentTurnVersionConflictException conflict) {
                // 不同 Runner/进程可能同时消费同一个业务消息。CAS 失败后仅在最新 Snapshot 已经包含
                // 同一 messageId 时折叠为幂等成功；不同消息的真实竞争仍原样抛出，不能静默丢消息。
                AgentTurn latest = restore(turn.getId());
                if (latest.hasProcessedUserMessage(userMessage.getMessageId())) return latest;
                throw conflict;
            }
        }
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
            AgentContextCompressionPolicy policy = agent.getCompressionPolicy();
            List<Message> history = chatMemory.loadModelHistory(
                conversationId, policy.isIncremental() ? Integer.MAX_VALUE : agent.getMaxAttachedMessages());
            List<Message> modelHistory = history;
            AgentTurn turn = AgentTurn.start(agent, history, userMessage, options);
            turn.bindConversation(conversationId, history.size());
            if (policy.isIncremental()) {
                List<Message> compressible = compressiblePrefix(history, policy.getKeepRecentTurns());
                List<Message> protectedTail = new ArrayList<>(history.subList(compressible.size(), history.size()));
                try {
                    AgentContextCompressionResult result = policy
                        .compress(conversationId, compressible,
                            () -> eventPublisher.notifyContextCompressionStarted(
                                turn, history.size(), compressible.size()));
                    modelHistory = new ArrayList<>(result.getModelMessages());
                    modelHistory.addAll(protectedTail);
                    turn.replaceConversationHistory(modelHistory);
                    turn.bindConversation(conversationId, modelHistory.size());
                    if (result.isCompressed()) {
                        eventPublisher.notifyContextCompressionCompleted(turn, result);
                    }
                } catch (RuntimeException error) {
                    eventPublisher.notifyContextCompressionFailed(turn, error);
                    throw error;
                }
            }
            prepareTurn(turn);
            saveInitialConversationSnapshot(turn);
            return turn;
        }
    }

    /**
     * 返回最近保护 Turn 之前的完整历史前缀。
     */
    private static List<Message> compressiblePrefix(List<Message> history, int keepRecentTurns) {
        if (history.isEmpty() || keepRecentTurns <= 0) return new ArrayList<>(history);
        int userMessages = 0;
        for (Message message : history) {
            if (message instanceof UserMessage) userMessages++;
        }
        int keep = Math.min(keepRecentTurns, userMessages);
        if (keep == 0) return new ArrayList<>(history);
        int targetUser = userMessages - keep;
        if (targetUser <= 0) return new ArrayList<>();
        int seen = 0;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i) instanceof UserMessage) {
                seen++;
                if (seen == targetUser + 1) {
                    return new ArrayList<>(history.subList(0, i));
                }
            }
        }
        return new ArrayList<>(history);
    }

    /**
     * 首次保存会话 Turn，回写 Store 分配版本后同步 ChatMemory 并发布事件。
     *
     * @param turn 尚未持久化的 READY Turn
     */
    private void saveInitialConversationSnapshot(AgentTurn turn) {
        synchronized (turn) {
            AgentTurnSnapshot saved = turnStore.save(turn.toSnapshot(), -1);
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
        // 先准备 Agent，再创建 Turn，确保初始 Snapshot 已包含完整可执行状态。
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
     * 持续推进，直到 Turn 终止或等待外部事件。
     *
     * <p>“阻塞”不是失败，而是已经保存了恢复所需状态并等待外部条件。典型阻塞状态包括等待用户输入、
     * 工具审批和重试时间。终止状态或阻塞状态到达后，本方法都会正常返回，由调用方读取
     * {@link AgentTurn#getStatus()} 决定后续动作。</p>
     *
     * @return 最新 Turn；阻塞状态表示需要审批、用户输入或重试时间
     */
    public AgentTurn runUntilBlocked(AgentTurn turn) {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        AgentTurn current = turn;
        ensurePreparedAndSnapshotSaved(current);
        refreshCancellation(current);
        // 普通状态持续单步推进；若阻塞期间收到取消信号，也要再执行一步完成 CANCELLED 落盘。
        while (!current.getStatus().isTerminal()
            && (!current.getStatus().isBlocked() || current.isCancellationRequested())) {
            step(current);
        }
        return current;
    }

    /**
     * 请求取消指定的 Agent Turn，并返回包含最新取消标记的 Turn。
     *
     * <p>这是任务级、可持久化的协作式取消。它适用于后台任务、等待状态和跨进程 Worker；不会主动
     * 中断已经发出的模型 HTTP 请求或正在运行的 Tool。用户交互中的“停止生成”应使用
     * {@link #stop(String)}。</p>
     *
     * @param turnId 要取消的 Turn ID
     * @return 已记录取消请求的最新 Turn
     */
    public AgentTurn cancel(String turnId) {
        boolean requested = turnStore.requestCancellation(turnId);
        AgentTurn turn = restore(turnId);
        if (requested) {
            eventPublisher.notifyCancellationRequested(turn);
        }
        return turn;
    }

    /**
     * 请求取消并尽快停止当前进程中该 Turn 的模型流、模型 Future 或工具 Future。
     *
     * <p>持久化取消仍由 Store 保证；如果执行发生在其他进程，本方法会退化为协作式取消，
     * 等待远端 Worker 在安全边界收束。</p>
     */
    public AgentTurn stop(String turnId) {
        boolean requested = turnStore.requestCancellation(turnId);
        AgentTurn turn = restore(turnId);
        executionRegistry.stop(turnId);
        if (requested) eventPublisher.notifyCancellationRequested(turn);
        if (turn.getStatus().isTerminal()) executionRegistry.clear(turnId);
        // Blocked Turn 没有活动句柄，需要本地推进一步立即落盘 CANCELLED。
        // READY Turn 可能正被另一个线程首次推进，不能在这里并发 runUntilBlocked。
        if (!turn.getStatus().isTerminal()
            && turn.getStatus().isBlocked()
            && !hasActiveLease(turn)) {
            return runUntilBlocked(turn);
        }
        return turn;
    }

    /**
     * 停止并等待当前进程中的执行句柄退出，然后返回最新 Turn 状态。
     *
     * <p>超时只表示底层实现没有及时响应中断，不会清除持久化取消请求。</p>
     */
    public AgentTurn stopAndWait(String turnId, long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        stop(turnId);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            executionRegistry.awaitIdle(turnId, timeoutMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        AgentTurn latest = restore(turnId);
        while (!latest.getStatus().isTerminal() && System.currentTimeMillis() < deadline) {
            if (latest.isCancellationRequested() && executionRegistry.isIdle(turnId)
                && latest.getStatus().isBlocked() && !hasActiveLease(latest)) {
                return runUntilBlocked(latest);
            }
            try {
                Thread.sleep(Math.min(10L, Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
            latest = restore(turnId);
        }
        return latest;
    }

    /**
     * 登记一个可由 {@link #stop(String)} 中断的当前进程内执行操作。
     */
    AgentExecutionRegistry.Registration registerExecution(String turnId, Runnable stopAction) {
        return executionRegistry.register(turnId, stopAction);
    }

    private boolean hasActiveLease(AgentTurn turn) {
        return StringUtil.hasText(turn.getLeaseOwner())
            && turn.getLeaseUntil() > turnStore.currentTimeMillis();
    }

    /**
     * @deprecated 请使用 {@link #cancel(String)}。保留该方法用于兼容已有业务代码。
     */
    @Deprecated
    public AgentTurn requestCancellation(String turnId) {
        return cancel(turnId);
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
     * <p>Suspension 同时记录等待类型、关联 ID 和恢复 ExecutionPoint。外部命令只有匹配这些信息才能恢复，
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
        AgentSuspension effective = suspension.withRequestedAt(turnStore.currentTimeMillis());
        turn.suspend(blockedStatusFor(effective.getType()), effective);
        saveSnapshot(turn);
        publishAfterStep(() -> eventPublisher.notifyTurnSuspended(turn, effective));
        return turn;
    }

    /**
     * 应用外部命令并在当前线程继续推进。
     *
     * <p>命令只作用于传入的阻塞 Turn。</p>
     */
    public AgentTurn resume(AgentTurn turn, AgentResumeCommand command) {
        return runUntilBlocked(submitResume(turn, command));
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
        // 用户消息可以回答旧输入或主动放弃旧等待；两种行为都不应先被过期策略终止。
        if (command.getType() != AgentResumeCommandType.USER_MESSAGE
            && command.getType() != AgentResumeCommandType.REPLAN_WITH_MESSAGE
            && resolveExpiredSuspension(turn, suspension)) {
            return turn;
        }
        if ((command.getType() == AgentResumeCommandType.USER_MESSAGE
            || command.getType() == AgentResumeCommandType.REPLAN_WITH_MESSAGE)
            && command.getUserMessage() != null
            && turn.hasProcessedUserMessage(command.getUserMessage().getMessageId())) {
            return turn;
        }
        int interruptionCount = turn.getToolInterruptions().size();
        // 先按 Suspension 类型校验并应用命令，任何不匹配的命令都不能改变运行状态。
        applyResumeCommand(turn, suspension, command);
        // 恢复到暂停前保存的模型或工具阶段，不从任务开头重新执行。
        AgentTurnExecutionPoint resumeExecutionPoint = suspension.getResumeExecutionPoint();
        if (resumeExecutionPoint == AgentTurnExecutionPoint.PROCESS_TOOLS
            && turn.getPendingToolCalls().isEmpty()) {
            resumeExecutionPoint = AgentTurnExecutionPoint.INVOKE_MODEL;
        }
        turn.resumeAt(resumeExecutionPoint);
        saveSnapshot(turn);
        List<AgentToolInterruption> interruptions = turn.getToolInterruptions();
        for (int index = interruptionCount; index < interruptions.size(); index++) {
            AgentToolInterruption interruption = interruptions.get(index);
            eventPublisher.notifyToolInterrupted(turn, interruption);
            if (interruption.getSuspensionType() == AgentSuspensionType.EXTERNAL_TOOL
                && interruption.getToolCallId().equals(suspension.getCorrelationId())) {
                eventPublisher.notifyExternalToolCancelRequested(turn, interruption);
            }
        }
        if (suspension.getType() == AgentSuspensionType.EXTERNAL_TOOL
            && (command.getType() == AgentResumeCommandType.TOOL_RESULT
            || command.getType() == AgentResumeCommandType.TOOL_ERROR)) {
            eventPublisher.notifyExternalToolResult(turn, suspension, command);
        }
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
     * 恢复指定 ID 的 Turn 但不继续执行。
     */
    public AgentTurn submitResume(String turnId, AgentResumeCommand command) {
        return submitResume(restore(turnId), command);
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

        List<Runnable> previousEvents = afterStepEvents.get();
        List<Runnable> deferredEvents = new ArrayList<>();
        afterStepEvents.set(deferredEvents);
        try {
            eventPublisher.publish(turn, AgentEventType.STEP_STARTED,
                objectAttributes("executionPoint", turn.getExecutionPoint()));
            AgentStepResult result = maxStepsReached
                ? maxStepsReached(turn) : stepCore(turn);
            eventPublisher.publish(turn, AgentEventType.STEP_COMPLETED,
                objectAttributes("status", turn.getStatus(),
                    "executionPoint", turn.getExecutionPoint(),
                    "toolMessageCount", result == null ? 0 : result.getToolMessages().size()));
            publishDeferredEvents(deferredEvents);
            publishTerminalEvent(turn);
            return result;
        } finally {
            if (previousEvents == null) {
                afterStepEvents.remove();
            } else {
                afterStepEvents.set(previousEvents);
            }
            if (turn.getStatus().isTerminal()) {
                eventPublisher.clearSequence(turn.getId());
                executionRegistry.clear(turn.getId());
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

        // 进入 Middleware 和内置 ToolCall 状态机。
        AgentStepResult result = proceedStep(turn,
            new AgentMiddlewareContext(this, turn, turn.getPrompt()), 0);
        if (result == null) {
            return handleFailure(turn, null,
                new IllegalStateException("Agent step returned null result"),
                turn.getExecutionPoint());
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
     * <p>ExecutionPoint 是恢复游标而不是业务状态：INVOKE_MODEL 表示下一步应请求模型，PROCESS_TOOLS 表示模型已经生成了
     * 尚未处理完的 ToolCall，FINISHED 表示运行已经结束。</p>
     *
     * <p>INVOKE_MODEL 阶段最多调用模型一次；PROCESS_TOOLS 阶段按顺序处理当前模型回合遗留的全部工具调用，并在
     * 每个结果写入后保存 Snapshot。</p>
     */
    private AgentStepResult executeToolCallingStep(AgentTurn turn) {
        AgentTurnExecutionPoint executionPoint = turn.getExecutionPoint();
        if (executionPoint == AgentTurnExecutionPoint.INVOKE_MODEL) {
            return executeModel(turn);
        }
        if (executionPoint == AgentTurnExecutionPoint.PROCESS_TOOLS) {
            return toolCallProcessor.executePendingTools(turn, null);
        }
        if (executionPoint == AgentTurnExecutionPoint.FINISHED) {
            return complete(turn, null, lastAiMessage(turn));
        }
        return handleFailure(turn, null,
            new IllegalStateException("Unsupported agent execution point: " + executionPoint),
            executionPoint);
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
     * 和 PROCESS_TOOLS ExecutionPoint，再执行工具；该顺序保证模型已经作出的工具决定可跨进程恢复。</p>
     */
    private AgentStepResult executeModel(AgentTurn turn) {
        Agent agent = turn.getAgent();
        if (turn.getSuccessfulModelInvocationCount()
            >= turn.getExecutionPolicy().getMaxIterations()) {
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
            // 有效模型响应结束当前连续失败链，但 retryCount 作为生命周期累计指标继续保留。
            turn.resetConsecutiveRetryCount();
            eventPublisher.notifyModelEnd(turn, response);
        } catch (RuntimeException error) {
            refreshCancellation(turn);
            return handleFailure(turn, null, error, AgentTurnExecutionPoint.INVOKE_MODEL);
        }
        refreshCancellation(turn);
        if (turn.isCancellationRequested()) {
            return cancelTurn(turn);
        }

        AiMessage message = response.getMessage();
        // 部分模型供应商不返回 ToolCall ID；在写入 Prompt 和 Snapshot 前补成稳定关联键。
        toolCallProcessor.ensureToolCallIds(turn, message);
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
        turn.moveTo(AgentTurnExecutionPoint.PROCESS_TOOLS);
        saveSnapshot(turn);
        return toolCallProcessor.executePendingTools(turn, response);
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
        Prompt modelPrompt = prompt;
        if (prompt instanceof com.agentsflex.core.prompt.MemoryPrompt) {
            AgentContextCompressionPolicy policy = turn.getAgent().getCompressionPolicy();
            AgentContextCompressor configuredCompressor = policy.getCompressor();
            if (policy.isIncremental() && turn.getConversationId() != null) configuredCompressor = null;
            final AgentContextCompressor compressor = configuredCompressor;
            AgentContextCompressor effectiveCompressor = compressor == null ? null
                : messages -> cachedCompression(turn, messages, compressor);
            modelPrompt = AgentContextWindow.build(
                (com.agentsflex.core.prompt.MemoryPrompt) prompt,
                turn.getAgent().getMaxAttachedTurns(),
                turn.getAgent().getMaxAttachedMessages(),
                turn.getAgent().getMaxAttachedTokens(),
                turn.getAgent().getContextTokenEstimator(),
                policy.isCompactCompletedToolTurns(),
                policy.getKeepRecentTurns(),
                effectiveCompressor, policy.getCompressionFailureStrategy());
        }
        return modelInvoker.invoke(turn, modelPrompt);
    }

    /**
     * 缓存单个 Turn 的即时摘要；压缩器失败时不写入缓存，便于下一次调用重试。
     */
    private List<Message> cachedCompression(AgentTurn turn, List<Message> messages,
                                            AgentContextCompressor compressor) {
        // 只用消息 ID 组成 key：新增工具结果或新一轮 UserMessage 会自然产生新 key，旧摘要不会误用。
        StringBuilder keyBuilder = new StringBuilder(turn.getId());
        for (Message message : messages) {
            keyBuilder.append('|').append(message == null ? "null" : message.getMessageId());
        }
        String key = keyBuilder.toString();
        List<Message> cached = compressionCache.get(key);
        if (cached != null) return AgentMessageUtils.copyMessages(cached);
        List<Message> result = compressor.compress(messages);
        if (result != null) compressionCache.putIfAbsent(key, AgentMessageUtils.copyMessages(result));
        return result;
    }

    /**
     * 将模型或工具异常统一转换为取消、持久化重试或最终失败状态。
     *
     * <p>安排重试时保存发生异常的 ExecutionPoint，使 Worker 到期恢复后从原模型或工具边界继续。方法只计算
     * {@code nextRunnableAt} 并返回阻塞结果，不在当前线程 sleep。</p>
     */
    AgentStepResult handleFailure(AgentTurn turn, AiMessageResponse response,
                                  RuntimeException error, AgentTurnExecutionPoint resumeExecutionPoint) {
        if (turn.isCancellationRequested()) {
            return cancelTurn(turn);
        }
        AgentModelFailure modelFailure = resumeExecutionPoint == AgentTurnExecutionPoint.INVOKE_MODEL
            ? AgentModelFailure.from(error, turn.getIterationCount()) : null;
        if (resumeExecutionPoint == AgentTurnExecutionPoint.INVOKE_MODEL) {
            // iterationCount 在请求前增加；此处单独标记失败，maxIterations 才能约束成功模型回合，
            // 同时保留真实请求尝试次数供成本和稳定性监控。
            turn.recordModelInvocationFailure(modelFailure);
        }
        AgentRetryPolicy retry = turn.getExecutionPolicy().getRetryPolicy();
        if (turn.getExecutionPolicy().getRetryDecider()
            .shouldRetry(turn, error,
                resumeExecutionPoint == AgentTurnExecutionPoint.PROCESS_TOOLS
                    && !turn.getPendingToolCalls().isEmpty()
                    ? turn.getPendingToolCalls().get(0) : null)
            && turn.getConsecutiveRetryCount() < retry.getMaxRetries()) {
            // consecutiveRetryCount + 1 表示当前失败链即将安排的重试序号，用于计算指数退避延迟。
            int retryAttempt = turn.getConsecutiveRetryCount() + 1;
            long runAt = System.currentTimeMillis() + retry.delayMillis(retryAttempt);
            if (resumeExecutionPoint == AgentTurnExecutionPoint.PROCESS_TOOLS && !turn.getPendingToolCalls().isEmpty()) {
                ToolCall call = turn.getPendingToolCalls().get(0);
                recordToolResume(turn, AgentToolCallProcessor.callKey(call), AgentToolResumeType.RETRY,
                    retryAttempt, runAt, null, error);
            }
            turn.scheduleRetry(error, resumeExecutionPoint, runAt);
            saveSnapshot(turn);
            publishAfterStep(() -> eventPublisher.notifyTurnSuspended(
                turn, turn.getSuspension()));
            publishAfterStep(() -> eventPublisher.notifyRetryScheduled(turn, error));
            return AgentStepResult.of(response, null, error);
        }
        if (modelFailure != null) {
            turn.waitForModel(error, modelFailure);
            saveSnapshot(turn);
            publishAfterStep(() -> eventPublisher.notifyTurnSuspended(
                turn, turn.getSuspension()));
            return AgentStepResult.of(response, null, error);
        }
        finalizeInterruptedHistory(turn, "turn failed: "
            + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        turn.markFailed(error);
        saveSnapshot(turn);
        return AgentStepResult.of(response, null, error);
    }

    /**
     * 保存最终消息，并将 Turn 转换为不可再次推进的 COMPLETED 状态。
     */
    AgentStepResult complete(AgentTurn turn, AiMessageResponse response, AiMessage message) {
        turn.markCompleted(message == null ? new AiMessage("") : message);
        saveSnapshot(turn);
        return AgentStepResult.of(response, null, null);
    }

    /**
     * 在安全边界响应单调取消信号并保存最终 CANCELLED 状态。
     */
    AgentStepResult cancelTurn(AgentTurn turn) {
        finalizeInterruptedHistory(turn, turn.getExecutionPolicy().getCancellationReason());
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
            result.setContent(renderInterruptedMessage(
                turn.getExecutionPolicy().getInterruptedToolMessageTemplate(), turn, call, reason));
            turn.getPrompt().addMessage(result);
        }
        turn.clearPendingToolCalls();
        turn.getPrompt().addMessage(new AiMessage(renderInterruptedMessage(
            turn.getExecutionPolicy().getInterruptedTurnMessageTemplate(), turn, null, reason)));
        turn.putMetadata("agentsflex.interruptedHistoryFinalized", true);
    }

    /**
     * 将执行策略中的收束消息模板渲染为本次 Turn 的实际消息内容。
     */
    private String renderInterruptedMessage(String template, AgentTurn turn,
                                            ToolCall call, String reason) {
        return template
            .replace("{reason}", reason == null ? "" : reason)
            .replace("{turnId}", turn.getId())
            .replace("{toolCallId}", call == null || call.getId() == null ? "" : call.getId())
            .replace("{toolName}", call == null || call.getName() == null ? "" : call.getName());
    }

    /**
     * 保存预算终止原因，避免调用方只能从通用失败信息推断成本限制。
     */
    AgentStepResult budgetExceeded(AgentTurn turn, String reason) {
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

    /**
     * 按产生顺序发布 Step 临界区结束后延迟的事件。
     *
     * @param events 不应包含空值的事件动作
     */
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
                clearCompressionCache(turn.getId());
                eventPublisher.notifyTurnComplete(turn);
                break;
            case FAILED:
                clearCompressionCache(turn.getId());
                eventPublisher.notifyTurnFailed(turn, turn.getError());
                break;
            case CANCELLED:
                clearCompressionCache(turn.getId());
                eventPublisher.notifyTurnCancelled(turn);
                break;
            case MAX_ITERATIONS_REACHED:
                clearCompressionCache(turn.getId());
                eventPublisher.notifyMaxIterationsReached(turn);
                break;
            case MAX_STEPS_REACHED:
                clearCompressionCache(turn.getId());
                eventPublisher.notifyMaxStepsReached(turn);
                break;
            case BUDGET_EXCEEDED:
                clearCompressionCache(turn.getId());
                eventPublisher.notifyBudgetExceeded(turn, turn.getBudgetExceededReason());
                break;
            default:
                // 非终止状态没有对应的 Turn 结束事件。
                break;
        }
    }

    private void clearCompressionCache(String turnId) {
        if (turnId == null) return;
        String prefix = turnId + "|";
        for (String key : compressionCache.keySet()) {
            if (key.startsWith(prefix)) compressionCache.remove(key);
        }
    }

    /**
     * 返回第一个已经超过的预算维度及实际用量；未超过时返回 null。
     *
     * <p>结果保留稳定的预算键名作为前缀，并附带当前用量和限制值，便于日志、事件消费者和用户界面
     * 直接展示。例如：{@code maxTotalTokens (used=120, limit=100)}。</p>
     *
     * @param beforeTool 是否即将执行一个新工具；工具次数只在该边界检查，避免已完成一次调用后被误判
     */
    String budgetExceededReason(AgentTurn turn, boolean beforeTool) {
        AgentBudget budget = turn.getExecutionPolicy().getBudget();
        long elapsed = System.currentTimeMillis() - turn.getCreatedAt();
        if (budget.getMaxDurationMillis() > 0 && elapsed >= budget.getMaxDurationMillis()) {
            return "maxDurationMillis (elapsed=" + elapsed + "ms, limit="
                + budget.getMaxDurationMillis() + "ms)";
        }
        if (budget.getMaxInputTokens() > 0 && turn.getInputTokens() > budget.getMaxInputTokens()) {
            return "maxInputTokens (used=" + turn.getInputTokens() + ", limit="
                + budget.getMaxInputTokens() + ")";
        }
        if (budget.getMaxOutputTokens() > 0 && turn.getOutputTokens() > budget.getMaxOutputTokens()) {
            return "maxOutputTokens (used=" + turn.getOutputTokens() + ", limit="
                + budget.getMaxOutputTokens() + ")";
        }
        if (budget.getMaxTotalTokens() > 0 && turn.getTotalTokens() > budget.getMaxTotalTokens()) {
            return "maxTotalTokens (used=" + turn.getTotalTokens() + ", limit="
                + budget.getMaxTotalTokens() + ")";
        }
        if (beforeTool && budget.getMaxToolCalls() > 0
            && turn.getToolCallCount() >= budget.getMaxToolCalls()) {
            return "maxToolCalls (used=" + turn.getToolCallCount() + ", limit="
                + budget.getMaxToolCalls() + ")";
        }
        return null;
    }

    /**
     * 校验 Turn 可以继续推进。
     *
     * @throws IllegalArgumentException Turn 为空时抛出
     * @throws IllegalStateException    Turn 已进入终态时抛出
     */
    private void validateStep(AgentTurn turn) {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        if (turn.getStatus().isTerminal()) {
            throw new IllegalStateException("turn is already terminal: " + turn.getStatus());
        }
    }

    /**
     * 校验模型响应存在、无错误且包含消息。
     *
     * @throws IllegalStateException 响应协议不完整时抛出
     */
    private void validateResponse(AiMessageResponse response) {
        if (response == null) {
            throw new IllegalStateException("chat model returned null response");
        }
        if (response.isError()) {
            response.throwIfError();
            throw new IllegalStateException("chat model returned an error: " + response.getErrorMessage());
        }
        if (response.getMessage() == null) {
            throw new IllegalStateException("chat model returned no message");
        }
    }

    /**
     * 从消息历史倒序查找最近的 AI 消息，供 FINISHED ExecutionPoint 完成运行。
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
     * 确保 Turn 已准备并保存初始 Snapshot。
     */
    private void ensurePreparedAndSnapshotSaved(AgentTurn turn) {
        prepareTurn(turn);
        if (turn.getVersion() < 0) {
            saveSnapshot(turn);
        }
    }

    private void prepareTurn(AgentTurn turn) {
        if (turn == null) throw new IllegalArgumentException("turn must not be null");
        prepareAgent(turn.getAgent());
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

    /**
     * 校验即将用于新 Turn 的 Agent 定义非空。
     */
    private void prepareAgent(Agent agent) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
    }

    /**
     * 加载并校验指定 ID 的当前生效 Agent。
     *
     * @return Loader 返回且 ID 匹配的 Agent
     */
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
            case EXTERNAL_TOOL:
                return AgentTurnStatus.WAITING_FOR_TOOL;
            case MODEL:
                return AgentTurnStatus.WAITING_FOR_MODEL;
            case RETRY:
                return AgentTurnStatus.RETRY_SCHEDULED;
            default:
                throw new IllegalStateException("Unsupported suspension type: " + type);
        }
    }

    /**
     * 校验恢复命令与当前 Suspension 匹配，并把命令携带的数据写入 Turn。
     *
     * <p>本方法只应用数据，不改变 Status 和 ExecutionPoint；调用方在校验完成后统一调用 resumeAt 并保存
     * Snapshot，避免部分应用一个无效命令。</p>
     */
    private void applyResumeCommand(AgentTurn turn, AgentSuspension suspension,
                                    AgentResumeCommand command) {
        if (suspension == null) {
            throw new IllegalStateException("blocked turn has no suspension data");
        }
        if (command.getType() == AgentResumeCommandType.USER_MESSAGE) {
            applyOrdinaryUserMessage(turn, suspension, command);
        } else if (command.getType() == AgentResumeCommandType.REPLAN_WITH_MESSAGE) {
            applyReplanMessage(turn, suspension, command);
        } else switch (suspension.getType()) {
            case USER_INPUT:
                applyUserInput(turn, suspension, command);
                break;
            case TOOL_APPROVAL:
                applyToolApproval(turn, suspension, command);
                break;
            case EXTERNAL_TOOL:
                applyExternalToolResult(turn, suspension, command);
                break;
            case MODEL:
                requireCommand(command, AgentResumeCommandType.RETRY_MODEL);
                requireCorrelation(suspension, command);
                turn.clearRetryError();
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
            default:
                throw new IllegalStateException("Unsupported suspension type: " + suspension.getType());
        }
        // RETRY 是调度器对同一失败链的自动推进，不能在这里重置，否则每次失败都重新获得完整预算。
        // 其他命令都表示人工输入、审批、工具回传或显式改变了运行条件，应开始新的连续失败链。
        if (command.getType() != AgentResumeCommandType.RETRY) {
            turn.resetConsecutiveRetryCount();
        }
        turn.putMetadata("lastResumeCommand", command.getType().name());
        turn.putMetadata("lastResumeCorrelationId",
            command.getCorrelationId() == null
                ? suspension.getCorrelationId() : command.getCorrelationId());
        if (!command.getMetadata().isEmpty()) {
            turn.putMetadata("lastResumeCommandMetadata",
                new LinkedHashMap<String, Object>(command.getMetadata()));
        }
    }

    /**
     * 按普通聊天语义处理阻塞期间收到的用户消息。
     *
     * <p>WAITING_FOR_USER 优先把消息交给当前输入请求；其他等待进入重规划。结构化业务表单和
     * 无法作为 ToolMessage 表达的多模态输入不会被猜测转换，而是保留为完整 UserMessage 重新规划。</p>
     */
    private void applyOrdinaryUserMessage(AgentTurn turn, AgentSuspension suspension,
                                          AgentResumeCommand command) {
        UserMessage message = requireUserMessage(command);
        if (suspension.getType() != AgentSuspensionType.USER_INPUT) {
            applyReplanMessage(turn, suspension, command);
            return;
        }

        // 业务工具的结构化表单必须通过 userInput(callId, data) 提交。自由文本无法可靠映射 Schema，
        // 因而普通消息在这里明确回退为重规划，绝不猜测字段名或伪造表单数据。
        if (TOOL_INPUT_TARGET.equals(suspension.getInputTarget())) {
            applyReplanMessage(turn, suspension, command);
            return;
        }

        if (!StringUtil.hasText(suspension.getCorrelationId())) {
            // 手工 USER_INPUT Suspension 没有 ToolCall 协议需要闭合；保留完整多模态消息作为回答。
            turn.getPrompt().addMessage(message);
            turn.markUserMessageProcessed(message.getMessageId());
            turn.putMetadata("lastUserMessageDisposition", "INPUT_RESPONSE");
            return;
        }

        List<ToolCall> pending = turn.getPendingToolCalls();
        ToolCall call = pending.isEmpty() ? null : pending.get(0);
        boolean requestUserInput = call != null
            && suspension.getCorrelationId().equals(AgentToolCallProcessor.callKey(call))
            && AgentUserInputTool.NAME.equals(call.getName());
        // request_user_input 的 ToolMessage 只能承载文本/JSON。纯图片等多模态回答改走重规划，
        // 这样附件仍会作为 UserMessage 进入模型上下文而不是被静默丢弃。
        if (!requestUserInput || hasNonTextContent(message)
            || !StringUtil.hasText(message.getTextContent())) {
            applyReplanMessage(turn, suspension, command);
            return;
        }
        AgentResumeCommand input = AgentResumeCommand.userInput(
                suspension.getCorrelationId(), message.getTextContent())
            .withMetadata(command.getMetadata());
        applyUserInput(turn, suspension, input);
        turn.markUserMessageProcessed(message.getMessageId());
        turn.putMetadata("lastUserMessageDisposition", "INPUT_RESPONSE");
    }

    /**
     * 无条件放弃当前等待，闭合全部 pending ToolCall，并把新消息交给模型重新规划。
     */
    private void applyReplanMessage(AgentTurn turn, AgentSuspension suspension,
                                    AgentResumeCommand command) {
        UserMessage message = requireUserMessage(command);
        String reason = "blocked operation superseded by a new user message";
        long occurredAt = turnStore.currentTimeMillis();
        for (ToolCall call : turn.getPendingToolCalls()) {
            if (call == null) continue;
            ToolMessage result = new ToolMessage();
            result.setToolCallId(AgentToolCallProcessor.callKey(call));
            result.setContent(renderInterruptedMessage(
                turn.getExecutionPolicy().getInterruptedToolMessageTemplate(),
                turn, call, reason));
            turn.getPrompt().addMessage(result);
            turn.addToolInterruption(new AgentToolInterruption(
                AgentToolCallProcessor.callKey(call), call.getName(), suspension.getType(),
                reason, occurredAt, message.getMessageId()));
        }
        turn.clearPendingToolCalls();
        turn.getPrompt().addMessage(message);
        turn.markUserMessageProcessed(message.getMessageId());
        turn.clearRetryError();
        turn.putMetadata("lastUserMessageDisposition", "REPLAN");
        turn.putMetadata("lastInterruptedSuspensionType", suspension.getType().name());
        turn.putMetadata("lastInterruptedSuspensionReason", reason);
    }

    private UserMessage requireUserMessage(AgentResumeCommand command) {
        UserMessage message = command.getUserMessage();
        if (message == null) {
            throw new IllegalArgumentException(command.getType() + " command requires userMessage");
        }
        return message;
    }

    private boolean hasNonTextContent(UserMessage message) {
        return message.getImageUrls() != null && !message.getImageUrls().isEmpty()
            || message.getAudioUrls() != null && !message.getAudioUrls().isEmpty()
            || message.getVideoUrls() != null && !message.getVideoUrls().isEmpty()
            || message.getFileUrls() != null && !message.getFileUrls().isEmpty();
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
        if (TOOL_INPUT_TARGET.equals(suspension.getInputTarget())) {
            if (!hasData) {
                throw new IllegalArgumentException(
                    "structured data is required for a suspended business tool");
            }
            if (!suspension.getCorrelationId().equals(AgentToolCallProcessor.callKey(call))
                || !call.getName().equals(suspension.getToolName())) {
                throw new IllegalStateException(
                    "user input suspension does not match the pending business ToolCall");
            }
            turn.putToolInputData(AgentToolCallProcessor.callKey(call), command.getData());
            Map<String, Object> metadata = new LinkedHashMap<>(suspension.getMetadata());
            metadata.putAll(command.getMetadata());
            recordToolResume(turn, AgentToolCallProcessor.callKey(call), AgentToolResumeType.FORM_INPUT,
                metadata, null);
            return;
        }
        if (!suspension.getCorrelationId().equals(AgentToolCallProcessor.callKey(call))
            || !AgentUserInputTool.NAME.equals(call.getName())) {
            throw new IllegalStateException(
                "user input suspension does not match the pending ToolCall");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "submitted");
        body.put("formKey", suspension.getFormKey());
        if (hasData) body.put("data", command.getData());
        if (hasContent) body.put("content", command.getContent());
        ToolMessage result = new ToolMessage();
        result.setToolCallId(AgentToolCallProcessor.callKey(call));
        result.setContent(JSON.toJSONString(body));
        turn.getPrompt().addMessage(result);
        turn.removeFirstPendingToolCall();
    }

    /**
     * 校验审批命令及 ToolCall 关联 ID，并按 POLICY/TOOL 阶段保存审计记录。
     *
     * <p>TOOL 阶段除保留兼容旧 UI 的“最近记录”外，还会按请求指纹保存独立审计项，确保同一
     * ToolCall 的多级批准不会在审计视图中互相覆盖。</p>
     */
    private void applyToolApproval(AgentTurn turn, AgentSuspension suspension,
                                   AgentResumeCommand command) {
        if (command.getType() != AgentResumeCommandType.APPROVE_TOOL
            && command.getType() != AgentResumeCommandType.REJECT_TOOL) {
            throw new IllegalArgumentException("APPROVE_TOOL or REJECT_TOOL command is required");
        }
        requireCorrelation(suspension, command);
        boolean approved = command.getType() == AgentResumeCommandType.APPROVE_TOOL;
        ToolApprovalStage stage = suspension.getApprovalStage();
        ToolApprovalRecord record = new ToolApprovalRecord(stage, approved,
            suspension.getApprovalRequestFingerprint(), suspension.getApprovalCode(),
            suspension.getMessage(), suspension.getApprovalReason(),
            suspension.getMetadata(), command.getMetadata(),
            approved ? null : command.getContent(), turnStore.currentTimeMillis());
        turn.putToolApprovalRecord(suspension.getCorrelationId(), record);
        if (approved) {
            Map<String, Object> metadata = new LinkedHashMap<>(suspension.getMetadata());
            metadata.putAll(command.getMetadata());
            recordToolResume(turn, suspension.getCorrelationId(), AgentToolResumeType.APPROVAL,
                metadata, null);
        }
        Map<String, Object> approvalAudit = new LinkedHashMap<>(suspension.getMetadata());
        putIfPresent(approvalAudit, "toolName", suspension.getToolName());
        putIfPresent(approvalAudit, "approvalOutcome", suspension.getApprovalOutcome());
        putIfPresent(approvalAudit, "approvalCode", suspension.getApprovalCode());
        putIfPresent(approvalAudit, "approvalReason", suspension.getApprovalReason());
        putIfPresent(approvalAudit, "approvalStage", stage);
        putIfPresent(approvalAudit, "requestFingerprint",
            suspension.getApprovalRequestFingerprint());
        approvalAudit.putAll(command.getMetadata());
        String auditPrefix = "toolApprovalAudit." + suspension.getCorrelationId();
        if (stage == ToolApprovalStage.TOOL
            && StringUtil.hasText(suspension.getApprovalRequestFingerprint())) {
            turn.putMetadata(auditPrefix + "." + stage + "."
                + suspension.getApprovalRequestFingerprint(), approvalAudit);
        }
        turn.putMetadata(auditPrefix + "." + stage, approvalAudit);
        // 保留旧审计键，旧 UI 仍能展示最近一次审批；真实授权判断只读取结构化阶段记录。
        turn.putMetadata(auditPrefix, approvalAudit);
        if (!approved
            && StringUtil.hasText(command.getContent())) {
            turn.putMetadata("toolRejectionReason." + suspension.getCorrelationId(), command.getContent());
        }
    }

    /**
     * 校验外部工具回传并直接生成与原 ToolCall 匹配的 ToolMessage。
     */
    private void applyExternalToolResult(AgentTurn turn, AgentSuspension suspension,
                                         AgentResumeCommand command) {
        if (command.getType() != AgentResumeCommandType.TOOL_RESULT
            && command.getType() != AgentResumeCommandType.TOOL_ERROR) {
            throw new IllegalArgumentException("TOOL_RESULT or TOOL_ERROR command is required");
        }
        requireCorrelation(suspension, command);
        if (command.getContent() == null) {
            throw new IllegalArgumentException("external tool result content must not be null");
        }
        long maxCharacters = turn.getExecutionPolicy().getExternalToolResultMaxCharacters();
        if (maxCharacters > 0 && command.getContent().length() > maxCharacters) {
            if (turn.getExecutionPolicy().getToolResultOverflowStrategy()
                == AgentToolResultOverflowStrategy.TRUNCATE) {
                command = new AgentResumeCommand(command.getType(),
                    AgentToolCallProcessor.truncateToolResult(command.getContent(), maxCharacters),
                    command.getCorrelationId(), command.getData(), command.getMetadata());
            } else {
                throw new IllegalArgumentException("external tool result exceeded maximum size: " + maxCharacters);
            }
        }
        List<ToolCall> pending = turn.getPendingToolCalls();
        if (pending.isEmpty()) {
            throw new IllegalStateException("external tool suspension has no pending ToolCall");
        }
        ToolCall call = pending.get(0);
        Tool tool = toolCallProcessor.resolveTool(turn, call);
        if (!suspension.getCorrelationId().equals(AgentToolCallProcessor.callKey(call))
            || !call.getName().equals(suspension.getToolName())
            || tool == null
            || tool.getExecutionTarget() != ToolExecutionTarget.EXTERNAL) {
            throw new IllegalStateException(
                "external tool suspension does not match the pending ToolCall");
        }
        ToolMessage result = new ToolMessage();
        result.setToolCallId(AgentToolCallProcessor.callKey(call));
        result.setContent(command.getContent());
        turn.getPrompt().addMessage(result);
        turn.removeFirstPendingToolCall();
    }

    /**
     * 统一检查审批、用户输入和外部工具的挂起期限。恢复命令在此之前不会改变 Turn 状态，
     * 因而过期命令不会留下半应用的审批结果或工具消息。
     */
    private boolean resolveExpiredSuspension(AgentTurn turn, AgentSuspension suspension) {
        if (suspension == null || suspension.getTimeoutMillis() <= 0
            || suspension.getRequestedAt() <= 0) {
            return false;
        }
        long elapsed = turnStore.currentTimeMillis() - suspension.getRequestedAt();
        if (elapsed < suspension.getTimeoutMillis()) {
            return false;
        }
        String subject;
        switch (suspension.getType()) {
            case TOOL_APPROVAL:
                subject = "tool approval";
                break;
            case USER_INPUT:
                subject = "user input";
                break;
            case EXTERNAL_TOOL:
                subject = "external tool result";
                break;
            default:
                subject = suspension.getType().name().toLowerCase();
        }
        String reason = subject + " has expired";
        AgentSuspensionExpirationStrategy strategy = turn.getExecutionPolicy()
            .getSuspensionExpirationStrategy();
        if (strategy == AgentSuspensionExpirationStrategy.REJECT_RESUME) {
            throw new IllegalStateException(reason);
        }
        finalizeInterruptedHistory(turn, reason);
        if (strategy == AgentSuspensionExpirationStrategy.CANCEL_TURN) {
            turn.markCancelled();
        } else {
            turn.markFailed(new IllegalStateException(reason));
        }
        saveSnapshot(turn);
        publishTerminalEvent(turn);
        return true;
    }

    /**
     * 记录按 ToolCall 隔离的恢复来源，供下一次工具函数执行读取。
     */
    private void recordToolResume(AgentTurn turn, String callId, AgentToolResumeType type,
                                  Map<String, ?> metadata, Throwable error) {
        recordToolResume(turn, callId, type, 0, 0L, metadata, error);
    }

    /**
     * 记录按 ToolCall 隔离的恢复来源及核心重试字段，供下一次工具函数执行读取。
     *
     * <p>重试序号和下一次执行时间是框架协议字段，直接写入 {@link AgentToolResumeInfo}；只有审批、
     * 表单和业务自定义内容继续放入 metadata。</p>
     */
    private void recordToolResume(AgentTurn turn, String callId, AgentToolResumeType type,
                                  int retryAttempt, long retryNextRunnableAt,
                                  Map<String, ?> metadata, Throwable error) {
        AgentToolResumeInfo previous = turn.getToolResumeInfo(callId);
        Map<String, Object> values = metadata == null
            ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(metadata);
        turn.putToolResumeInfo(callId, new AgentToolResumeInfo(type,
            previous.getResumeCount() + 1, retryAttempt, retryNextRunnableAt, values,
            error == null ? null : error.getClass().getName(),
            error == null ? null : error.getMessage()));
    }

    /**
     * 校验恢复命令类型与当前挂起分支要求完全一致。
     */
    private void requireCommand(AgentResumeCommand command, AgentResumeCommandType type) {
        if (command.getType() != type) {
            throw new IllegalArgumentException(type + " command is required");
        }
    }

    /**
     * 将非空的类型化挂起属性投影到审计 Map，兼容既有拒绝结果模板。
     */
    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    /**
     * 校验迟到恢复命令仍指向当前 Suspension 的关联对象。
     */
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
    void refreshCancellation(AgentTurn turn) {
        if (!turn.isCancellationRequested()) {
            AgentTurnSnapshot latest = turnStore.load(turn.getId());
            if (latest != null && latest.getState().isCancellationRequested()) {
                turn.requestCancellation();
            }
        }
    }

    /**
     * 表示恢复执行时，当前 Agent 已无法提供快照所记录的工具。
     */
    static final class AgentToolNotFoundException extends RuntimeException {
        /**
         * @param name Snapshot 中存在但当前 Agent 已无法解析的工具名
         */
        AgentToolNotFoundException(String name) {
            super("tool not found: " + name);
        }
    }

}

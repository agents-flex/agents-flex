/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentRunEvent;
import com.agentsflex.agent.event.AgentRunEventStore;
import com.agentsflex.agent.event.AgentRunEventEnricher;
import com.agentsflex.agent.event.AgentRunEventType;
import com.agentsflex.agent.event.InMemoryAgentRunEventStore;
import com.agentsflex.agent.event.AgentRuntimeEventStream;
import com.agentsflex.agent.event.AgentRuntimeEventListener;
import com.agentsflex.agent.event.AgentRuntimeEventType;
import com.agentsflex.agent.command.AgentRunCommand;
import com.agentsflex.agent.command.AgentRunCommandStore;
import com.agentsflex.agent.command.AgentWakeupListener;
import com.agentsflex.agent.command.InMemoryAgentRunCommandStore;
import com.agentsflex.agent.context.AgentArtifactReference;
import com.agentsflex.agent.context.AgentArtifactStore;
import com.agentsflex.agent.context.AgentContextUpdate;
import com.agentsflex.agent.context.InMemoryAgentArtifactStore;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentModelCallChain;
import com.agentsflex.agent.middleware.AgentStepChain;
import com.agentsflex.agent.middleware.AgentToolCallChain;
import com.agentsflex.agent.middleware.AgentToolCallContext;
import com.agentsflex.agent.loader.AgentLoader;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.mode.AgentExecutionContext;
import com.agentsflex.agent.store.AgentRunStore;
import com.agentsflex.agent.store.AgentRunVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.store.ParentChildRunSnapshots;
import com.agentsflex.agent.tool.AgentToolProgressEmitter;
import com.agentsflex.agent.tool.AgentToolInvocation;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.agent.tool.ToolErrorStrategy;
import com.agentsflex.agent.task.AgentPlanningPolicy;
import com.agentsflex.agent.task.AgentPlanningTool;
import com.agentsflex.agent.task.AgentTask;
import com.agentsflex.agent.task.AgentTaskPlan;
import com.agentsflex.agent.task.AgentTaskPlanStatus;
import com.agentsflex.agent.task.AgentTaskProgress;
import com.agentsflex.agent.task.AgentTaskStatus;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutor;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 创建、推进、暂停和恢复 {@link AgentRun} 的核心执行器。
 *
 * <p>Runner 可以理解为一个可持久化的状态机执行器。{@link Agent} 提供模型、指令、工具和执行
 * 策略，{@link AgentRun} 保存某条用户消息的可变运行状态，Runner 根据 Run 的
 * {@link AgentRunStatus} 和 {@link AgentRunPhase} 决定下一步调用模型、执行工具、等待外部事件，
 * 或结束运行。</p>
 *
 * <p>一次标准执行由三层组成：</p>
 * <ol>
 *     <li>{@link #runUntilBlocked(AgentRun)} 决定是否继续循环；</li>
 *     <li>{@link #step(AgentRun)} 完成取消、Lease、预算、规划和 Middleware 等通用检查；</li>
 *     <li>{@link com.agentsflex.agent.mode.AgentExecutionMode} 根据当前 Phase 推进具体执行逻辑。</li>
 * </ol>
 *
 * <p>默认执行模式使用模型原生 ToolCall。模型产生 ToolCall 后，Runner 先把调用及参数保存为
 * Checkpoint，再逐个完成审批、工具执行和 ToolMessage 写入。审批恢复时因此可以继续执行已经确认的
 * 原始 ToolCall，而不需要重新请求模型生成参数。</p>
 *
 * <p>Runner 同时负责预算检查、自动重试、暂停恢复、任务规划、父子 Run 协调和生命周期事件。
 * 所有需要跨进程恢复的状态最终通过 {@link AgentRunStore} 持久化；Runner 自身不长期保存任务状态，
 * 因而通常作为应用级对象复用。</p>
 *
 * <p>直接调用 {@code run(...)} 会在当前线程推进子 Run；分布式长任务应先调用 {@code start(...)}
 * 保存 READY Checkpoint，再由 AgentWorker 通过租约领取。不要让两个线程直接推进同一个
 * AgentRun 对象。</p>
 */
public final class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /**
     * 保存 Checkpoint、取消标记和 Worker 租约的运行存储。
     */
    private final AgentRunStore runStore;
    /**
     * 创建新任务和恢复旧任务时解析完整 Agent 的加载器。
     */
    private final AgentLoader agentLoader;
    /**
     * 保存可断点续读生命周期事件的追加式存储。
     */
    private final AgentRunEventStore eventStore;
    /**
     * 保存审批、用户输入等外部恢复命令的持久化收件箱。
     */
    private final AgentRunCommandStore commandStore;
    /**
     * 保存从 Prompt 卸载的大型工具结果。
     */
    private final AgentArtifactStore artifactStore;
    /**
     * 当前 JVM 内用于流式 UI 和低延迟追踪的实时事件流。
     */
    private final AgentRuntimeEventStream runtimeEventStream = new AgentRuntimeEventStream();
    /**
     * 统一模型调用、Token 统计和流式事件发布的适配器。
     */
    private final AgentModelInvoker modelInvoker = new AgentModelInvoker(runtimeEventStream);
    /**
     * 兼容粗粒度生命周期回调的线程安全监听器列表。
     */
    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();
    /**
     * 为持久化事件补充平台审计字段的增强器列表。
     */
    private final List<AgentRunEventEnricher> eventEnrichers = new CopyOnWriteArrayList<>();
    /**
     * 命令成功入箱后通知外部调度系统的监听器列表。
     */
    private final List<AgentWakeupListener> wakeupListeners = new CopyOnWriteArrayList<>();
    /**
     * 标识当前线程正在代表哪个 Worker 推进已领取的 Run。
     */
    private final ThreadLocal<String> activeWorkerId = new ThreadLocal<>();
    /**
     * 当前 Worker 本次领取得到的唯一租约令牌。
     *
     * <p>仅校验 workerId 无法区分同名 Worker 的两次领取；leaseId 用作 fencing token，阻止租约已经
     * 失效的旧执行者继续提交 Checkpoint。</p>
     */
    private final ThreadLocal<String> activeLeaseId = new ThreadLocal<>();

    /**
     * 创建全部使用进程内依赖的 Runner，适合测试和单实例试用。
     */
    public AgentRunner() {
        this(new InMemoryAgentRunStore(), new InMemoryAgentLoader());
    }

    /**
     * 创建可按需替换 Store 和 Loader 的 Runner 构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建自定义 RunStore 和 AgentLoader、其余依赖使用进程内实现的 Runner。
     */
    public AgentRunner(AgentRunStore runStore, AgentLoader agentLoader) {
        this(runStore, agentLoader, new InMemoryAgentRunEventStore(),
            new InMemoryAgentRunCommandStore(),
            new InMemoryAgentArtifactStore());
    }

    /**
     * 创建额外使用指定持久化事件 Store 的 Runner。
     */
    public AgentRunner(AgentRunStore runStore, AgentLoader agentLoader,
                       AgentRunEventStore eventStore) {
        this(runStore, agentLoader, eventStore,
            new InMemoryAgentRunCommandStore(), new InMemoryAgentArtifactStore());
    }

    /**
     * 创建显式提供全部可替换持久化依赖的 Runner。
     */
    public AgentRunner(AgentRunStore runStore, AgentLoader agentLoader,
                       AgentRunEventStore eventStore, AgentRunCommandStore commandStore,
                       AgentArtifactStore artifactStore) {
        if (runStore == null || agentLoader == null || eventStore == null
            || commandStore == null || artifactStore == null) {
            throw new IllegalArgumentException(
                "AgentRunner dependencies must not be null");
        }
        this.runStore = runStore;
        this.agentLoader = agentLoader;
        this.eventStore = eventStore;
        this.commandStore = commandStore;
        this.artifactStore = artifactStore;
    }

    /**
     * AgentRunner 依赖构建器。
     *
     * <p>未显式配置的组件使用进程内实现，适合测试和本地开发。多实例部署应至少替换 RunStore、
     * CommandStore、AgentLoader，并按需求替换 EventStore 和 ArtifactStore。AgentLoader 必须返回
     * 包含完整工具集合的可执行 Agent。</p>
     */
    public static final class Builder {
        private AgentRunStore runStore = new InMemoryAgentRunStore();
        private AgentLoader agentLoader = new InMemoryAgentLoader();
        private AgentRunEventStore eventStore = new InMemoryAgentRunEventStore();
        private AgentRunCommandStore commandStore = new InMemoryAgentRunCommandStore();
        private AgentArtifactStore artifactStore = new InMemoryAgentArtifactStore();

        /**
         * 设置 Checkpoint 与租约存储。
         */
        public Builder runStore(AgentRunStore value) {
            runStore = value;
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
         * 设置持久化生命周期事件存储。
         */
        public Builder eventStore(AgentRunEventStore value) {
            eventStore = value;
            return this;
        }

        /**
         * 设置持久化恢复命令收件箱。
         */
        public Builder commandStore(AgentRunCommandStore value) {
            commandStore = value;
            return this;
        }

        /**
         * 设置大型工具结果存储。
         */
        public Builder artifactStore(AgentArtifactStore value) {
            artifactStore = value;
            return this;
        }

        /**
         * 校验全部依赖并创建 Runner。
         */
        public AgentRunner build() {
            return new AgentRunner(runStore, agentLoader, eventStore,
                commandStore, artifactStore);
        }
    }

    /**
     * @return Runner 使用的 RunStore
     */
    public AgentRunStore getRunStore() {
        return runStore;
    }

    /**
     * @return Runner 使用的 AgentLoader
     */
    public AgentLoader getAgentLoader() {
        return agentLoader;
    }

    /**
     * @return Runner 使用的持久化 EventStore
     */
    public AgentRunEventStore getEventStore() {
        return eventStore;
    }

    /**
     * @return Runner 使用的恢复命令 Store
     */
    public AgentRunCommandStore getCommandStore() {
        return commandStore;
    }

    /**
     * @return Runner 使用的 Artifact Store
     */
    public AgentArtifactStore getArtifactStore() {
        return artifactStore;
    }

    /**
     * @return 当前 Runner 独享的进程内实时事件流
     */
    public AgentRuntimeEventStream getRuntimeEventStream() {
        return runtimeEventStream;
    }

    /**
     * 添加实时事件监听器并返回当前 Runner 以支持链式配置。
     *
     * <p>实时监听器在事件发布线程同步执行，适合流式 UI 和低延迟追踪，不提供跨进程可靠投递。</p>
     */
    public AgentRunner addRuntimeEventListener(AgentRuntimeEventListener listener) {
        runtimeEventStream.addListener(listener);
        return this;
    }

    /**
     * 添加命令入箱后的调度唤醒监听器。
     */
    public AgentRunner addWakeupListener(AgentWakeupListener listener) {
        if (listener != null) wakeupListeners.add(listener);
        return this;
    }

    /**
     * 添加粗粒度 Agent 生命周期监听器。
     *
     * <p>监听器用于观察而不是改变执行决策；单个监听器异常会被隔离，不会让当前 Run 失败。</p>
     */
    public AgentRunner addListener(AgentListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /**
     * 添加持久化事件属性增强器，用于写入租户、账号、模块和配置版本等审计维度。
     */
    public AgentRunner addEventEnricher(AgentRunEventEnricher enricher) {
        if (enricher != null) {
            eventEnrichers.add(enricher);
        }
        return this;
    }

    /**
     * 创建 Run 并同步推进到终止或阻塞状态。
     *
     * <p>该便捷入口等价于先调用 {@link #start(Agent, String)}，再调用
     * {@link #runUntilBlocked(AgentRun)}。返回值不一定已经完成，也可能正在等待审批、用户输入、
     * 子 Agent 或重试时间。</p>
     */
    public AgentRun run(Agent agent, String userInput) {
        return run(start(agent, userInput));
    }

    /**
     * 使用单次运行策略和元数据创建并执行任务。
     */
    public AgentRun run(Agent agent, String userInput, AgentRunOptions options) {
        return run(start(agent, userInput, options));
    }

    /**
     * 使用一条可包含文本、图片、音频、视频和文件的用户消息执行 Run。
     */
    public AgentRun run(Agent agent, UserMessage userMessage) {
        return run(start(agent, userMessage));
    }

    /**
     * 使用结构化用户消息和单次运行选项执行 Run。
     */
    public AgentRun run(Agent agent, UserMessage userMessage, AgentRunOptions options) {
        return run(start(agent, userMessage, options));
    }

    /**
     * 使用已有会话历史和本轮结构化消息创建并执行新的 Run。
     */
    public AgentRun run(Agent agent, List<? extends Message> conversationHistory,
                        UserMessage userMessage) {
        return run(start(agent, conversationHistory, userMessage));
    }

    /**
     * 使用已有会话历史、本轮结构化消息和单次运行选项创建并执行新的 Run。
     */
    public AgentRun run(Agent agent, List<? extends Message> conversationHistory,
                        UserMessage userMessage, AgentRunOptions options) {
        return run(start(agent, conversationHistory, userMessage, options));
    }

    /**
     * 在持续对话中使用本轮结构化消息创建并执行新的 Run。
     */
    public AgentRun run(AgentConversation conversation, UserMessage userMessage) {
        return run(start(conversation, userMessage));
    }

    /**
     * 在持续对话中使用纯文本便捷创建并执行新的 Run。
     */
    public AgentRun run(AgentConversation conversation, String userInput) {
        return run(conversation, new UserMessage(userInput));
    }

    /**
     * 在持续对话中使用本轮结构化消息和运行时覆盖参数创建并执行新的 Run。
     */
    public AgentRun run(AgentConversation conversation, UserMessage userMessage,
                        AgentRunOptions options) {
        return run(start(conversation, userMessage, options));
    }

    /**
     * 创建并保存一个尚未执行的 Run。
     */
    public AgentRun start(Agent agent, String userInput) {
        return start(agent, userInput, AgentRunOptions.defaults());
    }

    /**
     * 创建并保存一个带有运行时覆盖参数的任务。
     */
    public AgentRun start(Agent agent, String userInput, AgentRunOptions options) {
        return start(agent, new UserMessage(userInput), options);
    }

    /**
     * 创建并保存一个使用结构化用户消息、尚未执行的 Run。
     */
    public AgentRun start(Agent agent, UserMessage userMessage) {
        return start(agent, userMessage, AgentRunOptions.defaults());
    }

    /**
     * 创建并保存一个使用结构化用户消息和运行时覆盖参数的 Run。
     */
    public AgentRun start(Agent agent, UserMessage userMessage, AgentRunOptions options) {
        return start(agent, Collections.<Message>emptyList(), userMessage, options);
    }

    /**
     * 使用已有会话历史和本轮结构化消息创建并保存新的 Run。
     */
    public AgentRun start(Agent agent, List<? extends Message> conversationHistory,
                          UserMessage userMessage) {
        return start(agent, conversationHistory, userMessage, AgentRunOptions.defaults());
    }

    /**
     * 使用已有会话历史、本轮结构化消息和运行时覆盖参数创建并保存新的 Run。
     *
     * <p>这是无 Conversation 场景最终汇聚的创建入口。方法只建立可恢复的 READY 状态，不调用模型；
     * 初始 Checkpoint 成功后，Run 才会返回给调用方或后台调度器。</p>
     */
    public AgentRun start(Agent agent, List<? extends Message> conversationHistory,
                          UserMessage userMessage, AgentRunOptions options) {
        // 先准备 Agent 和规划工具，再创建 Run，确保初始 Snapshot 已包含完整可执行状态。
        prepareAgent(agent);
        AgentRun run = AgentRun.start(agent, conversationHistory, userMessage, options);
        prepareRun(run);
        // 初始 Checkpoint 使任务在第一次模型调用之前就可以被 Worker 发现和恢复。
        checkpoint(run);
        return run;
    }

    /**
     * 在持续对话中创建并保存一个尚未执行的 Run。
     */
    public AgentRun start(AgentConversation conversation, UserMessage userMessage) {
        return start(conversation, userMessage, AgentRunOptions.defaults());
    }

    /**
     * 在持续对话中使用纯文本便捷创建并保存新的 Run。
     */
    public AgentRun start(AgentConversation conversation, String userInput) {
        return start(conversation, new UserMessage(userInput));
    }

    /**
     * 在持续对话中创建并保存一个带有运行时覆盖参数的 Run。
     *
     * <p>同一 Conversation 同时只允许一个未终止 Run。创建过程会把本轮消息加入共享 Memory；如果
     * 初始 Checkpoint 保存失败，方法会恢复之前的 Memory 和活动 Run 标记，避免会话留下半创建状态。</p>
     */
    public AgentRun start(AgentConversation conversation, UserMessage userMessage,
                          AgentRunOptions options) {
        if (conversation == null) {
            throw new IllegalArgumentException("conversation must not be null");
        }
        synchronized (conversation) {
            // Conversation 的活动 Run 与共享 Memory 必须作为一个逻辑整体更新。
            conversation.assertCanStart();
            List<Message> previousMessages = conversation.getMessages();
            Agent agent = conversation.getAgent();
            prepareAgent(agent);
            AgentRun run = AgentRun.start(agent, conversation.getMemory(), userMessage, options)
                .attachConversation(conversation);
            prepareRun(run);
            run.putMetadata(AgentConversation.RUN_METADATA_KEY, conversation.getId());
            conversation.activate(run.getId());
            try {
                checkpoint(run);
                return run;
            } catch (RuntimeException error) {
                // Store 写入失败时撤销内存侧变更，调用方可以安全重试本轮消息。
                conversation.release(run.getId());
                replaceConversationMemory(conversation, previousMessages);
                throw error;
            }
        }
    }

    /**
     * 推进已经创建的 Run，语义等同于 {@link #runUntilBlocked(AgentRun)}。
     */
    public AgentRun run(AgentRun run) {
        return runUntilBlocked(run);
    }

    /**
     * 持续推进，直到根任务终止或等待外部事件。
     *
     * <p>“阻塞”不是失败，而是已经保存了恢复所需状态并等待外部条件。典型阻塞状态包括等待用户输入、
     * 工具审批、子 Run 和重试时间。终止状态或阻塞状态到达后，本方法都会正常返回，由调用方读取
     * {@link AgentRun#getStatus()} 决定后续动作。</p>
     *
     * <p>同步调用会在当前线程继续执行规划产生的子 Run，并在子 Run 终止后自动恢复父 Run。由
     * AgentWorker 持租约调用时只推进当前已领取 Run，子 Run 留给 Store 后续独立领取。</p>
     *
     * @return 最新父 Run；阻塞状态表示需要审批、用户输入、子 Run 或重试时间
     */
    public AgentRun runUntilBlocked(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        AgentRun current = run;
        AgentConversation conversation = run.getConversation();
        ensurePreparedAndCheckpointed(current);
        refreshCancellation(current);
        while (true) {
            // 普通状态持续单步推进；若阻塞期间收到取消信号，也要再执行一步完成 CANCELLED 落盘。
            while (!current.getStatus().isTerminal()
                && (!current.getStatus().isBlocked() || current.isCancellationRequested())) {
                step(current);
            }

            // 没有活动子任务时，当前 Run 已经到达本次调用的返回边界。
            AgentRun child = currentPlannedChild(current);
            if (child == null || current.isCancellationRequested()) return current;

            // Worker 只能推进自己通过 Store 领取的 Run，子 Run 必须另行领取并获得独立租约。
            if (activeWorkerId.get() != null) return current;

            // 同步模式递归执行子 Run；子 Run 若再次阻塞，则保持父 Run 的 WAITING_FOR_CHILD 状态返回。
            child = runUntilBlocked(child);
            if (!child.getStatus().isTerminal()) return current;

            // 子 Run 终止后把结果写回父 Run，再从父 Run 原来的恢复 Phase 继续外层循环。
            current = resumeParentFromChild(child);
            if (current == null) return run;
            if (conversation != null) current.attachConversation(conversation);
        }
    }

    /**
     * 将取消请求持久化到 Store，并返回包含最新取消标记的 Run。
     *
     * <p>该方法可以取消等待状态或由 Worker 执行的任务。取消是协作式的，正在阻塞的模型请求或
     * Tool 方法不会被强制中断，Runner 会在下一个安全边界终止运行。</p>
     */
    public AgentRun requestCancellation(String runId) {
        boolean requested = runStore.requestCancellation(runId);
        AgentRun run = restore(runId);
        AgentRun child = currentPlannedChild(run);
        if (child != null && !child.getStatus().isTerminal()) {
            runStore.requestCancellation(child.getId());
        }
        if (requested) {
            notifyCancellationRequested(run);
        }
        return run;
    }

    /**
     * 从 Store 恢复最新 Checkpoint。
     *
     * <p>方法按照快照中的 agentId 和 agentVersion 加载匹配定义，不使用当前生效版本，并附加空的
     * InvocationContext。</p>
     */
    public AgentRun restore(String runId) {
        return restore(runId, AgentInvocationContext.empty());
    }

    /**
     * 从 Store 恢复 Run，并附加当前进程使用的调用上下文。
     *
     * <p>InvocationContext 不参与持久化，Worker 应在每次恢复时重新提供租户身份和进程内服务。</p>
     */
    public AgentRun restore(String runId, AgentInvocationContext invocationContext) {
        // Snapshot 是恢复状态的事实来源；运行时对象不能从旧 JVM 内存中获取。
        AgentRunSnapshot snapshot = runStore.load(runId);
        if (snapshot == null) {
            throw new IllegalStateException("AgentRun checkpoint not found: " + runId);
        }
        // 必须加载创建 Run 时记录的版本，避免用最新配置解释旧模式状态或待执行 ToolCall。
        Agent agent = agentLoader.load(snapshot.getAgentId(), snapshot.getAgentVersion());
        if (agent == null) {
            throw new IllegalStateException("Agent cannot be loaded: " + snapshot.getAgentId()
                + ", version=" + snapshot.getAgentVersion());
        }
        // InvocationContext 不进入 Snapshot，由当前 API 请求或 Worker 在恢复时重新附加。
        return AgentRun.fromSnapshot(agent, snapshot).attachInvocationContext(invocationContext);
    }

    /**
     * 从 Store 恢复持续对话当前的活动 Run，并重新绑定 Conversation 的 Memory。
     *
     * <p>恢复后的消息以 Run Checkpoint 为准，确保审批、工具结果和中间模型消息不会丢失。</p>
     */
    public AgentRun restore(AgentConversation conversation) {
        if (conversation == null || !StringUtil.hasText(conversation.getActiveRunId())) {
            throw new IllegalStateException("conversation does not have an active run");
        }
        AgentRunSnapshot snapshot = runStore.load(conversation.getActiveRunId());
        if (snapshot == null) {
            throw new IllegalStateException("AgentRun checkpoint not found: "
                + conversation.getActiveRunId());
        }
        Agent agent = conversation.getAgent();
        if (!agent.getId().equals(snapshot.getAgentId())
            || !agent.getVersion().equals(snapshot.getAgentVersion())) {
            throw new IllegalStateException("conversation Agent does not match active run: "
                + snapshot.getAgentId() + ", version=" + snapshot.getAgentVersion());
        }
        replaceConversationMemory(conversation, snapshot.getMessages());
        AgentRun run = AgentRun.fromSnapshot(agent, snapshot).attachConversation(conversation);
        // Snapshot 恢复默认会创建独立 Memory，这里重新绑定 Conversation 的共享 Memory。
        run.getPrompt().setMemory(conversation.getMemory());
        return run;
    }

    /**
     * 从最新 Checkpoint 恢复指定 Run 并推进到下一个稳定边界。
     */
    public AgentRun runUntilBlocked(String runId) {
        return runUntilBlocked(restore(runId));
    }

    /**
     * 将 Run 置为等待外部事件的状态并立即保存。
     *
     * <p>Suspension 同时记录等待类型、关联 ID 和恢复 Phase。外部命令只有匹配这些信息才能恢复，
     * 从而避免把某次审批决定误用到另一个 ToolCall。</p>
     */
    public AgentRun suspend(AgentRun run, AgentSuspension suspension) {
        if (run == null || suspension == null) {
            throw new IllegalArgumentException("run and suspension must not be null");
        }
        if (run.getStatus().isTerminal()) {
            throw new IllegalStateException("terminal run cannot be suspended: " + run.getStatus());
        }
        assertLeaseOwnership(run);
        run.suspend(blockedStatusFor(suspension.getType()), suspension);
        checkpoint(run);
        notifyRunSuspended(run, suspension);
        return run;
    }

    /**
     * 应用外部命令并在当前线程继续推进。
     *
     * <p>根 Run 正在等待规划子 Run 时，命令会自动路由到实际阻塞的子 Run；子 Run 终止后再恢复并
     * 推进父 Run。</p>
     */
    public AgentRun resume(AgentRun run, AgentResumeCommand command) {
        AgentRun child = currentPlannedChild(run);
        if (child == null) return runUntilBlocked(submitResume(run, command));
        AgentRun resumedChild = resume(child, command);
        if (!resumedChild.getStatus().isTerminal()) return run;
        AgentRun parent = resumeParentFromChild(resumedChild);
        if (parent != null && run.getConversation() != null) {
            parent.attachConversation(run.getConversation());
        }
        return parent == null ? run : runUntilBlocked(parent);
    }

    /**
     * 提交恢复命令并保存为可运行状态，但不在当前线程继续执行。
     *
     * <p>事件消费者可以使用该方法唤醒任务，再由 AgentWorker 通过租约领取执行。</p>
     */
    public AgentRun submitResume(AgentRun run, AgentResumeCommand command) {
        return submitResume(run, command, null);
    }

    private AgentRun submitResume(AgentRun run, AgentResumeCommand command, String commandId) {
        if (run == null || command == null) {
            throw new IllegalArgumentException("run and command must not be null");
        }
        if (!run.getStatus().isBlocked()) {
            throw new IllegalStateException("run is not blocked: " + run.getStatus());
        }
        assertLeaseOwnership(run);
        AgentSuspension suspension = run.getSuspension();
        // 先按 Suspension 类型校验并应用命令，任何不匹配的命令都不能改变运行状态。
        applyResumeCommand(run, suspension, command);
        if (commandId != null) {
            // 消费持久化 Inbox 时记录命令 ID，使“应用成功但 acknowledge 前崩溃”仍可幂等恢复。
            run.putMetadata(processedCommandKey(commandId), true);
        }
        // 恢复到暂停前保存的模型或工具阶段，不从任务开头重新执行。
        run.resumeAt(suspension.getResumePhase());
        checkpoint(run);
        notifyRunResumed(run, command);
        return run;
    }

    /**
     * 恢复指定 ID 的阻塞 Run，并同步推进到下一个稳定边界。
     */
    public AgentRun resume(String runId, AgentResumeCommand command) {
        return resume(restore(runId), command);
    }

    /**
     * 恢复持续对话中正在等待外部输入的 Run。
     */
    public AgentRun resume(AgentConversation conversation, AgentResumeCommand command) {
        return resume(restore(conversation), command);
    }

    /**
     * 恢复指定 ID 的 Run 但不继续执行；规划场景会自动路由到当前活动子 Run。
     */
    public AgentRun submitResume(String runId, AgentResumeCommand command) {
        AgentRun run = restore(runId);
        AgentRun child = currentPlannedChild(run);
        return submitResume(child == null ? run : child, command);
    }

    /**
     * 将恢复命令持久化到收件箱，并在保存成功后发送唤醒通知。
     */
    public AgentRunCommand submitCommand(String runId, AgentResumeCommand command) {
        return submitCommand(UUID.randomUUID().toString(), runId, command);
    }

    /**
     * 使用调用方提供的命令 ID 幂等提交恢复命令。
     *
     * <p>该方法只把命令写入持久化 Inbox，不直接修改 Run。相同 commandId 和相同内容可安全重试；
     * 相同 ID 对应不同决定会被拒绝，防止网络重试把批准变成拒绝或反向覆盖。</p>
     */
    public AgentRunCommand submitCommand(String commandId, String runId,
                                         AgentResumeCommand command) {
        AgentRunCommand existing = commandStore.load(commandId);
        if (existing != null) {
            // API 请求超时后可能使用同一 commandId 重试；内容一致时直接返回首次提交结果。
            AgentResumeCommand savedCommand = existing.getCommand();
            if (savedCommand.getType() == command.getType()
                && Objects.equals(savedCommand.getContent(), command.getContent())
                && Objects.equals(savedCommand.getCorrelationId(), command.getCorrelationId())
                && Objects.equals(savedCommand.getMetadata(), command.getMetadata())) {
                return existing;
            }
            throw new IllegalArgumentException(
                "commandId is already bound to another resume decision: " + commandId);
        }
        AgentRun run = restore(runId);
        AgentRun child = currentPlannedChild(run);
        if (child != null) {
            // 根任务等待规划子任务时，审批或用户输入实际属于子 Run，应路由到真实阻塞对象。
            run = child;
            runId = child.getId();
        }
        if (!run.getStatus().isBlocked()) {
            throw new IllegalStateException("run is not blocked: " + run.getStatus());
        }
        AgentRunCommand saved = commandStore.submit(
            AgentRunCommand.pending(commandId, runId, command));
        emitRuntime(run, AgentRuntimeEventType.COMMAND_SUBMITTED,
            objectAttributes("commandId", saved.getCommandId(),
                "commandType", saved.getCommand().getType()));
        for (AgentWakeupListener listener : wakeupListeners) {
            try {
                // 唤醒只是降低处理延迟；命令已经持久化，通知丢失后仍可由 Worker 轮询兜底。
                listener.onWakeup(saved);
            } catch (RuntimeException error) {
                log.warn("Agent wakeup listener failed", error);
            }
        }
        return saved;
    }

    /**
     * 领取并应用一批持久化恢复命令，使对应 Run 重新进入可调度状态。
     *
     * <p>同一 commandId 会在 Run metadata 中记录已处理标记，避免命令确认前进程退出导致重复应用。
     * 单条命令处理失败会释放重试，累计领取三次后标记为最终失败。</p>
     */
    public int processCommands(String workerId, long leaseMillis, int limit) {
        // Command 使用独立 Lease 领取，避免多个 Worker 同时应用同一项外部决定。
        List<AgentRunCommand> commands = commandStore.claim(workerId,
            System.currentTimeMillis(), leaseMillis, limit);
        int completed = 0;
        for (AgentRunCommand item : commands) {
            AgentRun run = null;
            try {
                run = restore(item.getRunId());
                if (!Boolean.TRUE.equals(run.getMetadata().get(processedCommandKey(item.getCommandId())))) {
                    submitResume(run, item.getCommand(), item.getCommandId());
                }
                // 只有恢复状态成功写入 Checkpoint 后才能确认命令消费完成。
                commandStore.acknowledge(item.getCommandId(), workerId);
                emitRuntime(run, AgentRuntimeEventType.COMMAND_CONSUMED,
                    objectAttributes("commandId", item.getCommandId()));
                completed++;
            } catch (RuntimeException error) {
                // 临时故障释放命令供后续领取；连续失败达到阈值后保留最终失败状态供人工排查。
                if (item.getAttempts() >= 3) {
                    commandStore.fail(item.getCommandId(), workerId, error.getMessage());
                } else {
                    commandStore.release(item.getCommandId(), workerId, error.getMessage());
                }
                if (run != null) emitRuntime(run, AgentRuntimeEventType.COMMAND_FAILED,
                    objectAttributes("commandId", item.getCommandId(), "error", error.getMessage()));
            }
        }
        return completed;
    }

    private String processedCommandKey(String commandId) {
        return "agent.command.processed." + commandId;
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
    public AgentRunSnapshot checkpoint(AgentRun run) {
        synchronized (run) {
            // 同步块保证同一 JVM 中 Snapshot 构造、Store CAS 和本地版本更新不可交错。
            assertLeaseOwnership(run);
            AgentRunSnapshot saved = runStore.save(run.toSnapshot(), run.getVersion());
            run.updateVersion(saved.getVersion());
            if (saved.isCancellationRequested()) {
                run.requestCancellation();
            }
            notifyCheckpoint(run, saved);
            return saved;
        }
    }

    /**
     * 推进当前模型或工具步骤。
     *
     * <p>一次调用最多调用模型一次，但可以顺序处理该模型回合产生的全部 ToolCall。方法返回
     * {@link AgentStepResult} 描述本步结果；是否继续下一步由 {@link #runUntilBlocked(AgentRun)} 决定。</p>
     *
     * <p>步骤开始/结束实时事件在最外层发布，Conversation 的 Memory 和活动 Run 标记也在此统一更新，
     * 因而自定义执行模式不需要重复处理这些通用生命周期工作。</p>
     */
    public AgentStepResult step(AgentRun run) {
        emitRuntime(run, AgentRuntimeEventType.STEP_STARTED,
            objectAttributes("phase", run == null ? null : run.getPhase()));
        try {
            AgentStepResult result = stepCore(run);
            emitRuntime(run, AgentRuntimeEventType.STEP_COMPLETED,
                objectAttributes("stepType", result == null ? null : result.getType()));
            return result;
        } finally {
            updateConversation(run);
            if (run != null && run.getStatus().isTerminal()) {
                runtimeEventStream.release(run.getId());
            }
        }
    }

    /**
     * Run 结束时允许 Conversation 接收下一轮消息；阻塞和运行状态继续保留活动 Run。
     */
    private void updateConversation(AgentRun run) {
        if (run == null || run.getConversation() == null) {
            return;
        }
        if (run.getStatus().isTerminal()) {
            replaceConversationMemory(run.getConversation(), run.getConversationHistory());
            run.getConversation().release(run.getId());
        }
    }

    /**
     * 使用 Checkpoint 消息重建 Conversation Memory，并排除由 Agent 定义重新注入的系统消息。
     */
    private void replaceConversationMemory(AgentConversation conversation,
                                           List<? extends Message> messages) {
        synchronized (conversation) {
            conversation.getMemory().clear();
            if (messages == null) {
                return;
            }
            for (Message message : messages) {
                if (!(message instanceof com.agentsflex.core.message.SystemMessage)) {
                    conversation.getMemory().addMessage(AgentMessageUtils.copyMessage(message));
                }
            }
        }
    }

    /**
     * 以责任链方式执行 step Middleware，链尾调用 Agent 配置的执行模式。
     *
     * <p>每个 Middleware 可以在调用 next 前后观察或增强步骤，但应保持链只推进一次。</p>
     */
    private AgentStepResult proceedStep(AgentRun run, AgentMiddlewareContext context, int index) {
        List<AgentMiddleware> middlewares = run == null
            ? Collections.<AgentMiddleware>emptyList() : run.getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
            return run.getAgent().getExecutionMode()
                .step(new AgentExecutionContext(this, run));
        }
        AgentMiddleware middleware = middlewares.get(index);
        AgentStepChain chain = next -> proceedStep(run, next, index + 1);
        return middleware.aroundStep(context, chain);
    }

    /**
     * 执行不包含 step Middleware 包装的通用单步状态机。
     */
    private AgentStepResult stepCore(AgentRun run) {
        // 先确认当前状态可推进，并补齐旧调用方可能尚未保存的初始状态。
        validateStep(run);
        ensurePreparedAndCheckpointed(run);

        // Lease 和持久化取消标记必须在任何模型或工具副作用之前检查。
        assertLeaseOwnership(run);
        refreshCancellation(run);
        if (run.isCancellationRequested()) {
            return cancelRun(run);
        }
        if (run.getStatus().isBlocked()) {
            // 阻塞 Run 只能通过类型化 ResumeCommand 改回可运行状态，step 本身不能越过等待边界。
            return AgentStepResult.of(AgentStepType.BLOCKED, null, null, null);
        }
        if (run.markStarted()) {
            notifyRunStart(run);
        }
        // 时间和累计 Token 预算在每一步入口检查；工具次数还会在具体工具执行前再次检查。
        String budgetReason = budgetExceededReason(run, false);
        if (budgetReason != null) {
            return budgetExceeded(run, budgetReason);
        }

        if (run.getStepCount() >= run.getExecutionPolicy().getMaxSteps()) {
            run.markMaxStepsReached();
            checkpoint(run);
            notifyMaxStepsReached(run);
            return AgentStepResult.of(AgentStepType.MAX_STEPS_REACHED,
                null, null, null);
        }
        run.incrementStep();

        // 已存在的任务计划优先于下一次模型调用推进，避免计划任务与普通对话循环互相竞争。
        AgentStepResult planningResult = advanceTaskPlan(run);
        if (planningResult != null) return planningResult;

        // 规划没有产生独立动作时，再进入 Middleware 和当前 AgentExecutionMode。
        AgentStepResult result = proceedStep(run,
            new AgentMiddlewareContext(this, run, run.getPrompt()), 0);
        if (result == null) {
            return handleFailure(run, null,
                new IllegalStateException("AgentExecutionMode returned null step result"),
                run.getPhase());
        }
        // 模型或工具执行期间控制面可能提交取消，返回本步之前再同步一次单调取消信号。
        refreshCancellation(run);
        if (run.isCancellationRequested() && !run.getStatus().isTerminal()) {
            return cancelRun(run);
        }
        return result;
    }

    /**
     * 供默认模式及组合模式调用的模型原生 ToolCall 单步执行方法。
     *
     * <p>Phase 是恢复游标而不是业务状态：MODEL 表示下一步应请求模型，TOOLS 表示模型已经生成了
     * 尚未处理完的 ToolCall，FINISHED 表示运行已经结束。自定义模式可复用本方法，同时保留框架的
     * Checkpoint、审批、预算和 Middleware 语义。</p>
     *
     * <p>MODEL 阶段最多调用模型一次；TOOLS 阶段按顺序处理当前模型回合遗留的全部工具调用，并在
     * 每个结果写入后保存 Checkpoint。</p>
     */
    public AgentStepResult executeToolCallingStep(AgentRun run) {
        AgentRunPhase phase = run.getPhase();
        if (phase == AgentRunPhase.MODEL) {
            return executeModel(run);
        }
        if (phase == AgentRunPhase.TOOLS) {
            return executePendingTools(run, null);
        }
        if (phase == AgentRunPhase.FINISHED) {
            return complete(run, null, lastAiMessage(run));
        }
        return handleFailure(run, null,
            new IllegalStateException("Unsupported agent phase: " + phase), phase);
    }

    /**
     * 供自定义模式正常结束 Run。
     */
    public AgentStepResult completeFromMode(AgentRun run, AiMessage message) {
        if (run == null || message == null) {
            throw new IllegalArgumentException("run and message must not be null");
        }
        refreshCancellation(run);
        if (run.isCancellationRequested()) {
            return cancelRun(run);
        }
        run.getPrompt().addMessage(message);
        return complete(run, null, message);
    }

    /**
     * 供自定义模式按统一重试和失败策略处理异常。
     */
    public AgentStepResult failFromMode(AgentRun run, Throwable error) {
        if (run == null || error == null) {
            throw new IllegalArgumentException("run and error must not be null");
        }
        refreshCancellation(run);
        RuntimeException runtimeError = error instanceof RuntimeException
            ? (RuntimeException) error : new RuntimeException(error);
        return handleFailure(run, null, runtimeError, run.getPhase());
    }

    /**
     * 创建子 Run，并让父 Run 等待子任务完成。
     *
     * <p>目标 Agent 使用 loadActive 加载。父等待快照和新子快照通过 RunStore 的原子接口一起保存，
     * 避免只创建子 Run 或只暂停父 Run 的部分状态。</p>
     */
    public AgentRun startChild(AgentRun parent, String childAgentId, String input) {
        // 创建子 Run 会同时改变父 Run 状态，因此必须仍持有父 Run 的有效 Lease。
        assertLeaseOwnership(parent);
        // 新子任务使用目标 Agent 当前生效版本；一旦创建，其具体版本会写入子 Snapshot。
        Agent childAgent = agentLoader.loadActive(childAgentId);
        if (childAgent == null) {
            throw new IllegalStateException("Active child agent cannot be loaded: " + childAgentId);
        }
        prepareAgent(childAgent);
        AgentRun child = AgentRun.startChild(childAgent, input, parent);
        prepareRun(child);
        AgentTaskPlan plan = parent.getTaskPlan();
        if (plan != null && plan.getActiveTask() == null && plan.getNextTask() != null) {
            // 先把计划任务与 childRunId 绑定，查询进度时才能定位真实执行对象。
            AgentTask task = plan.getNextTask();
            validateTaskAgent(parent, task, childAgentId);
            parent.updateTaskPlan(plan.startTask(task.getId(), child.getId(),
                System.currentTimeMillis()));
            child.putMetadata("agentTaskPlanId", plan.getId());
            child.putMetadata("agentTaskId", task.getId());
        }
        AgentSuspension suspension = AgentSuspension.child(child.getId());
        AgentRunSnapshot parentWaiting = parent.toSnapshot().toBuilder()
            .status(AgentRunStatus.WAITING_FOR_CHILD)
            .phase(suspension.getResumePhase())
            .suspension(suspension)
            .build();
        // 父等待状态与子 READY 状态必须原子提交，避免出现孤儿子任务或永久等待的父任务。
        ParentChildRunSnapshots saved = runStore.saveParentAndChild(
            parentWaiting, parent.getVersion(), child.toSnapshot());
        parent.suspend(AgentRunStatus.WAITING_FOR_CHILD, suspension);
        parent.updateVersion(saved.getParent().getVersion());
        child.updateVersion(saved.getChild().getVersion());
        notifyCheckpoint(parent, saved.getParent());
        notifyCheckpoint(child, saved.getChild());
        notifyRunSuspended(parent, suspension);
        notifyChildStarted(parent, child);
        if (parent.getTaskPlan() != null && parent.getTaskPlan().getActiveTask() != null) {
            notifyTaskStarted(parent, parent.getTaskPlan().getActiveTask(), child);
        }
        return child;
    }

    /**
     * 将终止子 Run 的结果交回正在等待它的父 Run。
     *
     * <p>该方法具备幂等检查：父 Run 已不再等待当前 childRunId 时不会重复写入消息。任务结果按父
     * Agent 的 planningPolicy 限制写回长度，子 Run 中的完整最终输出不会被修改。</p>
     */
    public AgentRun resumeParentFromChild(AgentRun child) {
        if (child == null || !child.getStatus().isTerminal()
            || !StringUtil.hasText(child.getParentRunId())) {
            return null;
        }
        AgentRun parent = restore(child.getParentRunId());
        AgentSuspension suspension = parent.getSuspension();
        // 已经恢复过或正在等待其他子 Run 时直接返回，保证补偿扫描可以重复调用。
        if (parent.getStatus() != AgentRunStatus.WAITING_FOR_CHILD || suspension == null
            || !child.getId().equals(suspension.getCorrelationId())) {
            return parent;
        }
        // 只向父 Prompt 写入结构化摘要；子 Run 的完整消息与结果仍保留在自身 Snapshot 中。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("childRunId", child.getId());
        result.put("agentId", child.getAgent().getId());
        result.put("status", child.getStatus().name());
        String taskResult = limitTaskResult(parent, child.getFinalOutput());
        result.put("output", taskResult);
        if (!Objects.equals(taskResult, child.getFinalOutput())) {
            result.put("outputTruncated", true);
        }
        result.put("error", child.getError() == null ? null : child.getError().getMessage());
        AgentTaskPlan plan = parent.getTaskPlan();
        if (plan != null && plan.getActiveTask() != null
            && child.getId().equals(plan.getActiveTask().getChildRunId())) {
            // 将子 Run 的终止状态映射为计划任务状态，并按策略决定停止、重规划或继续。
            AgentTask activeTask = plan.getActiveTask();
            AgentTaskStatus taskStatus = child.getStatus() == AgentRunStatus.COMPLETED
                ? AgentTaskStatus.COMPLETED
                : (child.getStatus() == AgentRunStatus.CANCELLED
                ? AgentTaskStatus.CANCELLED : AgentTaskStatus.FAILED);
            String taskError = child.getError() == null
                ? (child.getStatus() == AgentRunStatus.COMPLETED ? null : child.getStatus().name())
                : child.getError().getMessage();
            AgentTaskPlan updated = plan.finishActiveTask(taskStatus,
                taskResult, taskError, System.currentTimeMillis());
            if (taskStatus != AgentTaskStatus.COMPLETED
                && parent.getAgent().getPlanningPolicy().getFailureStrategy()
                == AgentPlanningPolicy.FailureStrategy.STOP) {
                updated = updated.stop(taskError, System.currentTimeMillis());
            } else if (taskStatus != AgentTaskStatus.COMPLETED
                && canReplan(parent.getAgent().getPlanningPolicy(), updated)) {
                updated = updated.beginReplanning(System.currentTimeMillis());
            }
            parent.updateTaskPlan(updated);
            parent.addChildUsage(child);
            notifyTaskFinished(parent, activeTask, child, taskStatus);
        }
        parent.getPrompt().addUserMessage("Child Agent result: " + JSON.toJSONString(result));
        // CHILD_COMPLETED 命令复用统一恢复校验，并把父 Run 放回暂停前的执行 Phase。
        return submitResume(parent, AgentResumeCommand.childCompleted(child.getId()));
    }

    /**
     * 查询根 Run 中的计划以及当前子 Run 的真实阻塞状态。
     */
    public AgentTaskProgress getTaskProgress(String runId) {
        AgentRun root = restore(runId);
        AgentTaskPlan plan = root.getTaskPlan();
        if (plan == null) return null;
        AgentRun child = currentPlannedChild(root);
        return new AgentTaskProgress(plan,
            child == null ? root.getStatus() : child.getStatus(),
            child == null ? root.getSuspension() : child.getSuspension());
    }

    /**
     * 修复子 Run 已终止但父 Run 尚未收到完成信号的状态。
     *
     * <p>该补偿操作是幂等的，适合每次 Worker 轮询前执行。多个 Worker 同时修复时，
     * 父 Run 的乐观锁只允许一个写入成功，其他竞争者会在后续轮询看到已恢复状态。</p>
     */
    public int recoverCompletedChildren(int limit) {
        int recovered = 0;
        for (AgentRunSnapshot snapshot : runStore.findTerminalChildrenWithWaitingParent(limit)) {
            try {
                AgentRun child = restore(snapshot.getRunId());
                AgentRun parent = resumeParentFromChild(child);
                if (parent != null && !parent.getStatus().isBlocked()) recovered++;
            } catch (AgentRunVersionConflictException ignored) {
                // 另一个 Worker 已经完成相同修复，下一次查询会自然过滤该父 Run。
            } catch (IllegalStateException error) {
                log.debug("Completed child recovery skipped, childRunId={}",
                    snapshot.getRunId(), error);
            }
        }
        return recovered;
    }

    /**
     * 在指定 Worker 的租约上下文中推进已经领取的 Run。
     */
    AgentRun runLeased(AgentRun run, String workerId, String leaseId) {
        if (!workerId.equals(run.getLeaseOwner())
            || leaseId == null || !leaseId.equals(run.getLeaseId())
            || run.getLeaseUntil() <= runStore.currentTimeMillis()) {
            throw new IllegalStateException("AgentRun lease is not active for worker: " + workerId);
        }
        activeWorkerId.set(workerId);
        activeLeaseId.set(leaseId);
        try {
            if (run.getStatus() == AgentRunStatus.RETRY_SCHEDULED) {
                return resume(run, AgentResumeCommand.retry());
            }
            return runUntilBlocked(run);
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
    private AgentStepResult executeModel(AgentRun run) {
        Agent agent = run.getAgent();
        if (run.getIterationCount() >= run.getExecutionPolicy().getMaxIterations()) {
            run.markMaxIterationsReached();
            checkpoint(run);
            notifyMaxIterationsReached(run);
            return AgentStepResult.of(AgentStepType.MAX_ITERATIONS_REACHED,
                null, null, null);
        }

        // 迭代次数表示模型调用次数，在发起请求前增加，失败的模型请求同样消耗一次尝试。
        run.incrementIteration();
        notifyModelStart(run);
        AiMessageResponse response;
        try {
            // 上下文管理器可压缩历史或替换大型内容；变更后先落盘再把新 Prompt 发送给模型。
            AgentContextUpdate update = agent.getContextManager()
                .prepare(run, run.getInvocationContext());
            if (update != null && update.isChanged()) {
                checkpoint(run);
                emitRuntime(run, AgentRuntimeEventType.CONTEXT_COMPACTED,
                    objectAttributes("removedMessageCount", update.getRemovedMessageCount(),
                        "remainingMessageCount", update.getRemainingMessageCount()));
            }
            // 模型 Middleware 以责任链包裹最终调用，可用于 tracing、缓存或受控 Prompt 增强。
            AgentMiddlewareContext middlewareContext = new AgentMiddlewareContext(
                this, run, run.getPrompt());
            response = proceedModelCall(run, middlewareContext, 0);
            validateResponse(response);
            notifyModelEnd(run, response);
        } catch (RuntimeException error) {
            return handleFailure(run, null, error, AgentRunPhase.MODEL);
        }
        refreshCancellation(run);
        if (run.isCancellationRequested()) {
            return cancelRun(run);
        }

        AiMessage message = response.getMessage();
        // 部分模型供应商不返回 ToolCall ID；在写入 Prompt 和 Snapshot 前补成稳定关联键。
        ensureToolCallIds(run, message);
        run.addUsage(message);
        run.getPrompt().addMessage(message);
        String budgetReason = budgetExceededReason(run, false);
        if (budgetReason != null) {
            return budgetExceeded(run, budgetReason);
        }

        if (!message.hasToolCalls()) {
            // 不含 ToolCall 的 AI 消息是默认执行模式的最终回答。
            return complete(run, response, message);
        }

        // 先保存模型决策，再执行可能产生外部副作用的工具。
        run.setPendingToolCalls(message.getToolCalls());
        run.moveTo(AgentRunPhase.TOOLS);
        checkpoint(run);
        return executePendingTools(run, response);
    }

    /**
     * 递归构造模型 Middleware 责任链，链尾由 AgentModelInvoker 统一调用 ChatModel。
     */
    private AiMessageResponse proceedModelCall(AgentRun run, AgentMiddlewareContext context, int index) {
        List<AgentMiddleware> middlewares = run.getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
            return invokeModel(run, context.getPrompt());
        }
        AgentMiddleware middleware = middlewares.get(index);
        AgentModelCallChain chain = next -> proceedModelCall(run, next, index + 1);
        return middleware.aroundModelCall(context, chain);
    }

    /**
     * 调用模型适配器，并由适配器发布细粒度流式事件。
     */
    private AiMessageResponse invokeModel(AgentRun run, Prompt prompt) {
        return modelInvoker.invoke(run, prompt);
    }

    /**
     * 逐个处理待执行 ToolCall，并在每个结果写入后保存 Checkpoint。
     */
    private AgentStepResult executePendingTools(AgentRun run, AiMessageResponse response) {
        List<ToolMessage> results = new ArrayList<>();
        while (!run.getPendingToolCalls().isEmpty()) {
            // 每个 ToolCall 都是独立副作用边界，开始前重新检查取消和工具次数预算。
            if (run.isCancellationRequested()) {
                return cancelRun(run);
            }
            String budgetReason = budgetExceededReason(run, true);
            if (budgetReason != null) {
                return budgetExceeded(run, budgetReason);
            }

            // 始终处理队首调用；成功写入 ToolMessage 后才从 pending 列表移除。
            ToolCall call = run.getPendingToolCalls().get(0);
            if (AgentPlanningTool.NAME.equals(call.getName())
                || AgentPlanningTool.UPDATE_NAME.equals(call.getName())) {
                try {
                    // 规划工具只转换 Run 内部状态，不经过业务工具审批和外部执行器。
                    boolean creating = AgentPlanningTool.NAME.equals(call.getName());
                    ToolMessage planned = creating
                        ? createTaskPlan(run, call) : updateTaskPlan(run, call);
                    appendToolResult(run, call, planned);
                    results.add(planned);
                    if (creating) notifyPlanCreated(run, run.getTaskPlan());
                    else notifyPlanUpdated(run, run.getTaskPlan());
                    continue;
                } catch (RuntimeException error) {
                    return handleFailure(run, response, error, AgentRunPhase.TOOLS);
                }
            }
            Tool tool = resolveTool(run, call);
            if (tool == null) {
                // 恢复后找不到原工具表示 Agent 版本不完整，不能跳过调用继续生成答案。
                return handleFailure(run, response,
                    new AgentToolNotFoundException(call.getName()), AgentRunPhase.TOOLS);
            }

            // 已恢复的审批决定优先；首次遇到 ToolCall 时才执行动态审批策略。
            Boolean approval = run.getToolApproval(callKey(call));
            ToolApprovalDecision decision = approval == null
                ? run.getAgent().getToolApprovalPolicy().decide(run, call, tool)
                : (approval ? ToolApprovalDecision.ALLOW : ToolApprovalDecision.DENY);
            if (decision == null) {
                return handleFailure(run, response,
                    new IllegalStateException("ToolApprovalPolicy returned null"), AgentRunPhase.TOOLS);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.REQUIRE_APPROVAL) {
                // Suspension 保存当前 ToolCall 关联 ID 和 TOOLS 恢复点，审批后不会重新调用模型。
                AgentSuspension suspension = AgentSuspension.toolApproval(
                    callKey(call), call.getName(), decision);
                suspend(run, suspension);
                notifyToolApprovalRequested(run, call, decision);
                return AgentStepResult.of(AgentStepType.BLOCKED, response, results, null);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.DENY) {
                // 拒绝不是运行时异常，而是结构化 ToolMessage；模型可据此向用户解释或选择替代方案。
                ToolMessage rejected = buildToolRejectedMessage(run, call, decision);
                appendToolResult(run, call, rejected);
                results.add(rejected);
                continue;
            }

            notifyToolStart(run, call);
            ToolMessage completedResult;
            try {
                run.incrementToolCallCount();
                completedResult = executeTool(run, tool, call);
            } catch (RuntimeException error) {
                notifyToolError(run, call, error);
                if (run.getExecutionPolicy().getToolErrorStrategy()
                    == ToolErrorStrategy.RETURN_ERROR_TO_MODEL) {
                    // 将错误交给模型时仍生成与原 ToolCall 匹配的 ToolMessage，保持协议完整。
                    completedResult = buildToolErrorMessage(call, error);
                } else {
                    return handleFailure(run, response, error, AgentRunPhase.TOOLS);
                }
            }
            // Checkpoint 异常必须直接交给调用方，不能被误判为工具执行异常。
            appendToolResult(run, call, completedResult);
            results.add(completedResult);
            notifyToolEnd(run, call, completedResult);
            refreshCancellation(run);
        }

        // 当前模型回合的全部工具调用已处理，下一 step 应让模型读取 ToolMessage 并继续判断。
        run.moveTo(AgentRunPhase.MODEL);
        checkpoint(run);
        return AgentStepResult.of(AgentStepType.TOOLS_EXECUTED, response, results, null);
    }

    /**
     * 原子语义上提交一个工具结果：必要时外置大内容、追加消息、移除 pending 调用并保存 Checkpoint。
     *
     * <p>只有 Checkpoint 成功后调用才算被运行时确认；具备外部副作用的 Tool 仍应使用
     * {@link AgentToolInvocation} ID 在业务侧实现幂等。</p>
     */
    private void appendToolResult(AgentRun run, ToolCall call, ToolMessage result) {
        maybeOffloadToolResult(run, call, result);
        run.getPrompt().addMessage(result);
        run.removeFirstPendingToolCall();
        checkpoint(run);
    }

    /**
     * 解析模型对内置规划工具的调用，并把计划直接写入当前 Run。
     *
     * <p>内置工具只描述状态转换，不执行外部代码，因此不进入业务工具审批、中间件和工具解析流程。</p>
     */
    private ToolMessage createTaskPlan(AgentRun run, ToolCall call) {
        if (!run.isPlanningEnabled()) {
            throw new IllegalArgumentException("task planning is not enabled for this run");
        }
        if (run.getTaskPlan() != null) {
            throw new IllegalArgumentException("an AgentRun can only create one task plan");
        }
        Map<String, Object> arguments = call.getArgsMap();
        JSONObject object = arguments == null
            ? new JSONObject() : new JSONObject(arguments);
        String goal = object.getString("goal");
        JSONArray values = object.getJSONArray("tasks");
        AgentPlanningPolicy policy = run.getAgent().getPlanningPolicy();
        if (!StringUtil.hasText(goal) || values == null || values.isEmpty()) {
            throw new IllegalArgumentException("planning goal and tasks are required");
        }
        if (values.size() > policy.getMaxTasks()) {
            throw new IllegalArgumentException("task count exceeds maxTasks: "
                + policy.getMaxTasks());
        }
        List<AgentTask> tasks = parseTasks(run, values);
        AgentTaskPlan plan = AgentTaskPlan.create(goal, tasks);
        run.updateTaskPlan(plan);

        ToolMessage result = new ToolMessage();
        result.setToolCallId(callKey(call));
        result.setContent(JSON.toJSONString(objectAttributes(
            "accepted", true, "planId", plan.getId(), "taskCount", tasks.size())));
        return result;
    }

    /**
     * 校验并应用模型对尚未执行任务的调整。
     */
    private ToolMessage updateTaskPlan(AgentRun run, ToolCall call) {
        AgentTaskPlan plan = run.getTaskPlan();
        AgentPlanningPolicy policy = run.getAgent().getPlanningPolicy();
        if (plan == null || plan.getStatus() != AgentTaskPlanStatus.REPLANNING) {
            throw new IllegalArgumentException("task plan is not waiting for an update");
        }
        if (plan.getRevisionCount() >= policy.getMaxReplans()) {
            throw new IllegalArgumentException("task plan has reached maxReplans");
        }
        Map<String, Object> arguments = call.getArgsMap();
        JSONObject object = arguments == null
            ? new JSONObject() : new JSONObject(arguments);
        String reason = object.getString("reason");
        JSONArray values = object.getJSONArray("tasks");
        if (!StringUtil.hasText(reason) || values == null || values.isEmpty()) {
            throw new IllegalArgumentException("revision reason and tasks are required");
        }
        AgentTaskPlan updated = plan.revisePending(parseTasks(run, values), reason,
            policy.isTaskRevisionAllowed(), policy.isTaskAppendAllowed(),
            policy.getMaxTasks(), System.currentTimeMillis());
        run.updateTaskPlan(updated);

        ToolMessage result = new ToolMessage();
        result.setToolCallId(callKey(call));
        result.setContent(JSON.toJSONString(objectAttributes(
            "accepted", true, "planId", updated.getId(),
            "revisionCount", updated.getRevisionCount(),
            "pendingTaskCount", values.size())));
        return result;
    }

    /**
     * 将规划工具中的任务数组转换为经过委派白名单校验的任务定义。
     */
    private List<AgentTask> parseTasks(AgentRun run, JSONArray values) {
        AgentPlanningPolicy policy = run.getAgent().getPlanningPolicy();
        List<AgentTask> tasks = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            JSONObject value = values.getJSONObject(index);
            String id = value == null ? null : value.getString("id");
            String title = value == null ? null : value.getString("title");
            String description = value == null ? null : value.getString("description");
            String assignedAgentId = value == null ? null : value.getString("assignedAgentId");
            if (!StringUtil.hasText(id) || !StringUtil.hasText(title)
                || !StringUtil.hasText(description)) {
                throw new IllegalArgumentException(
                    "each task requires id, title and description, index=" + index);
            }
            if (!policy.canDelegateTo(run.getAgent().getId(), assignedAgentId)) {
                throw new IllegalArgumentException(
                    "task agent is not allowed: " + assignedAgentId);
            }
            tasks.add(AgentTask.builder(title).id(id).description(description)
                .expectedOutput(value.getString("expectedOutput"))
                .assignedAgentId(assignedAgentId).position(index).build());
        }
        return tasks;
    }

    /**
     * 在普通 step 循环中调度计划的下一任务，或进入最终汇总阶段。
     */
    private AgentStepResult advanceTaskPlan(AgentRun run) {
        AgentTaskPlan plan = run.getTaskPlan();
        if (plan == null || plan.getStatus() == AgentTaskPlanStatus.COMPLETED
            || plan.getStatus() == AgentTaskPlanStatus.FINALIZING
            || plan.getStatus() == AgentTaskPlanStatus.REPLANNING) return null;
        if (plan.getActiveTask() != null) {
            return AgentStepResult.of(AgentStepType.BLOCKED, null, null, null);
        }
        AgentTask next = plan.getNextTask();
        if (next == null) {
            // 没有待执行任务后进入最终汇总；策略允许时可直接使用最后一个任务结果结束。
            run.updateTaskPlan(plan.beginFinalizing(System.currentTimeMillis()));
            checkpoint(run);
            if (!run.getAgent().getPlanningPolicy().isFinalSummaryRequired()) {
                AiMessage finalMessage = new AiMessage(lastTaskResult(plan));
                run.getPrompt().addMessage(finalMessage);
                return complete(run, null, finalMessage);
            }
            return null;
        }
        String agentId = StringUtil.hasText(next.getAssignedAgentId())
            ? next.getAssignedAgentId() : run.getAgent().getId();
        // 每个计划任务都创建普通子 Run，从而复用审批、重试、预算、事件和 Worker 调度。
        startChild(run, agentId, taskInput(plan, next));
        return AgentStepResult.of(AgentStepType.BLOCKED, null, null, null);
    }

    /**
     * 返回计划当前关联的子 Run；不存在活动计划任务时返回 null。
     */
    private AgentRun currentPlannedChild(AgentRun parent) {
        if (parent == null || parent.getStatus() != AgentRunStatus.WAITING_FOR_CHILD) return null;
        AgentTaskPlan plan = parent.getTaskPlan();
        AgentTask task = plan == null ? null : plan.getActiveTask();
        return task == null || !StringUtil.hasText(task.getChildRunId())
            ? null : restore(task.getChildRunId(), parent.getInvocationContext());
    }

    /**
     * 校验模型选择的目标 Agent 满足当前 Agent 的委派约束。
     */
    private void validateTaskAgent(AgentRun parent, AgentTask task, String childAgentId) {
        String expected = StringUtil.hasText(task.getAssignedAgentId())
            ? task.getAssignedAgentId() : parent.getAgent().getId();
        if (!expected.equals(childAgentId)
            || !parent.getAgent().getPlanningPolicy()
            .canDelegateTo(parent.getAgent().getId(), childAgentId)) {
            throw new IllegalArgumentException("task cannot be delegated to Agent: " + childAgentId);
        }
    }

    /**
     * 生成只包含总体目标和当前任务的子 Run 输入。
     */
    private String taskInput(AgentTaskPlan plan, AgentTask task) {
        return "总体目标：" + plan.getGoal()
            + "\n当前任务：" + task.getTitle()
            + "\n执行要求：" + task.getDescription()
            + (StringUtil.hasText(task.getExpectedOutput())
            ? "\n期望输出：" + task.getExpectedOutput() : "")
            + "\n请只完成当前任务，并返回可供父 Agent 汇总的结果。";
    }

    private String lastTaskResult(AgentTaskPlan plan) {
        List<AgentTask> tasks = plan.getTasks();
        for (int index = tasks.size() - 1; index >= 0; index--) {
            if (StringUtil.hasText(tasks.get(index).getResult())) return tasks.get(index).getResult();
        }
        return "任务计划已执行完成";
    }

    /**
     * 判断失败后的计划是否仍有一次受策略约束的调整机会。
     */
    private boolean canReplan(AgentPlanningPolicy policy, AgentTaskPlan plan) {
        return policy.getMaxReplans() > plan.getRevisionCount()
            && (policy.isTaskRevisionAllowed() || policy.isTaskAppendAllowed())
            && plan.getNextTask() != null;
    }

    /**
     * 限制复制到父计划和父提示词中的子任务结果长度，子 Run 原始结果保持不变。
     */
    private String limitTaskResult(AgentRun parent, String value) {
        if (value == null) return null;
        int maxLength = parent.getAgent().getPlanningPolicy().getTaskResultMaxLength();
        if (maxLength <= 0 || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "\n[子任务结果已截断，完整内容保留在子 Run 中]";
    }

    /**
     * 从恢复出的当前 Agent 定义中按名称解析工具；工具对象本身不保存在 Snapshot。
     */
    private Tool resolveTool(AgentRun run, ToolCall call) {
        return call == null ? null : run.getAgent().getTool(call.getName());
    }

    /**
     * 将模型、工具或执行模式异常统一转换为取消、持久化重试或最终失败状态。
     *
     * <p>安排重试时保存发生异常的 Phase，使 Worker 到期恢复后从原模型或工具边界继续。方法只计算
     * {@code nextRunAt} 并返回阻塞结果，不在当前线程 sleep。</p>
     */
    private AgentStepResult handleFailure(AgentRun run, AiMessageResponse response,
                                          RuntimeException error, AgentRunPhase resumePhase) {
        if (error instanceof AgentRunCancelledException || run.isCancellationRequested()) {
            return cancelRun(run);
        }
        AgentRetryPolicy retry = run.getExecutionPolicy().getRetryPolicy();
        if (isRetryable(error) && run.getRetryCount() < retry.getMaxRetries()) {
            // retryCount + 1 表示即将安排的重试序号，用于计算指数退避延迟。
            long runAt = System.currentTimeMillis() + retry.delayMillis(run.getRetryCount() + 1);
            run.scheduleRetry(error, resumePhase, runAt);
            checkpoint(run);
            notifyRunSuspended(run, run.getSuspension());
            notifyRetryScheduled(run, error);
            return AgentStepResult.of(AgentStepType.BLOCKED, response, null, error);
        }
        run.markFailed(error);
        checkpoint(run);
        notifyRunFailed(run, error);
        return AgentStepResult.of(AgentStepType.FAILED, response, null, error);
    }

    /**
     * 参数错误和缺失工具属于确定性配置问题，重复执行不会自行恢复。
     */
    private boolean isRetryable(RuntimeException error) {
        return !(error instanceof AgentToolNotFoundException)
            && !(error instanceof IllegalArgumentException);
    }

    /**
     * 保存最终消息、收束计划状态，并将 Run 转换为不可再次推进的 COMPLETED 状态。
     */
    private AgentStepResult complete(AgentRun run, AiMessageResponse response, AiMessage message) {
        AgentTaskPlan plan = run.getTaskPlan();
        if (plan != null && plan.getStatus() == AgentTaskPlanStatus.REPLANNING) {
            plan = plan.stop("模型未提交计划调整，剩余任务已跳过", System.currentTimeMillis());
        }
        if (plan != null && plan.getStatus() == AgentTaskPlanStatus.FINALIZING) {
            run.updateTaskPlan(plan.complete(System.currentTimeMillis()));
        }
        run.markCompleted(message == null ? new AiMessage("") : message);
        checkpoint(run);
        notifyRunComplete(run);
        return AgentStepResult.of(AgentStepType.COMPLETED, response, null, null);
    }

    /**
     * 在安全边界响应单调取消信号并保存最终 CANCELLED 状态。
     */
    private AgentStepResult cancelRun(AgentRun run) {
        run.markCancelled();
        checkpoint(run);
        notifyRunCancelled(run);
        return AgentStepResult.of(AgentStepType.CANCELLED, null, null, null);
    }

    /**
     * 保存预算终止原因，避免调用方只能从通用失败信息推断成本限制。
     */
    private AgentStepResult budgetExceeded(AgentRun run, String reason) {
        run.markBudgetExceeded(reason);
        checkpoint(run);
        notifyBudgetExceeded(run, reason);
        return AgentStepResult.of(AgentStepType.BUDGET_EXCEEDED,
            null, null, null);
    }

    /**
     * 返回第一个已经超过的预算维度；未超过时返回 null。
     *
     * @param beforeTool 是否即将执行一个新工具；工具次数只在该边界检查，避免已完成一次调用后被误判
     */
    private String budgetExceededReason(AgentRun run, boolean beforeTool) {
        AgentBudget budget = run.getExecutionPolicy().getBudget();
        long elapsed = System.currentTimeMillis() - run.getCreatedAt();
        if (budget.getMaxDurationMillis() > 0 && elapsed >= budget.getMaxDurationMillis()) {
            return "maxDurationMillis";
        }
        if (budget.getMaxInputTokens() > 0 && run.getInputTokens() > budget.getMaxInputTokens()) {
            return "maxInputTokens";
        }
        if (budget.getMaxOutputTokens() > 0 && run.getOutputTokens() > budget.getMaxOutputTokens()) {
            return "maxOutputTokens";
        }
        if (budget.getMaxTotalTokens() > 0 && run.getTotalTokens() > budget.getMaxTotalTokens()) {
            return "maxTotalTokens";
        }
        if (beforeTool && budget.getMaxToolCalls() > 0
            && run.getToolCallCount() >= budget.getMaxToolCalls()) {
            return "maxToolCalls";
        }
        return null;
    }

    private void validateStep(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        if (run.getStatus().isTerminal()) {
            throw new IllegalStateException("run is already terminal: " + run.getStatus());
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
     * <p>{@link AgentToolInvocation} 提供跨恢复稳定的调用身份，Tool 可将其作为业务幂等键。调用顺序为
     * Agent Middleware、ToolInterceptor、Tool 函数；InvocationContext 和进度发射器通过工具上下文
     * attributes 传入，不写入工具参数 Schema。</p>
     */
    private ToolMessage executeTool(AgentRun run, Tool tool, ToolCall call) {
        List<ToolInterceptor> interceptors = run.getAgent().getToolInterceptors();
        AgentToolInvocation invocation = new AgentToolInvocation(
            run.getId(), run.getRootRunId(), run.getParentRunId(),
            run.getAgent().getId(), run.getAgent().getVersion(), callKey(call), tool.getName());
        AgentToolCallContext middlewareContext = new AgentToolCallContext(this, run, tool, call);
        Object value = proceedToolCall(run, middlewareContext, 0, invocation, interceptors);
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
    private Object proceedToolCall(AgentRun run, AgentToolCallContext context, int index,
                                   AgentToolInvocation invocation,
                                   List<ToolInterceptor> interceptors) {
        List<AgentMiddleware> middlewares = run.getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
            // 这些 attributes 只在本次 JVM 调用链存在，不会混入模型可见的工具参数。
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put(AgentToolInvocation.CONTEXT_ATTRIBUTE, invocation);
            attributes.put(AgentInvocationContext.CONTEXT_ATTRIBUTE, run.getInvocationContext());
            attributes.put(AgentToolProgressEmitter.CONTEXT_ATTRIBUTE,
                (AgentToolProgressEmitter) (message, data) -> emitRuntime(run,
                    AgentRuntimeEventType.TOOL_PROGRESS,
                    mergeObjectAttributes(objectAttributes("toolCallId", callKey(context.getToolCall()),
                        "toolName", context.getTool().getName(), "message", message), data)));
            return new ToolExecutor(context.getTool(), context.getToolCall(), interceptors)
                .execute(attributes);
        }
        AgentMiddleware middleware = middlewares.get(index);
        AgentToolCallChain chain = next -> proceedToolCall(run, next, index + 1,
            invocation, interceptors);
        return middleware.aroundToolCall(context, chain);
    }

    /**
     * 将超过策略阈值的工具结果保存到 Artifact Store，并用稳定引用替换消息正文。
     */
    private void maybeOffloadToolResult(AgentRun run, ToolCall call, ToolMessage result) {
        String content = result.getContent();
        if (!run.getAgent().getToolResultOffloadPolicy()
            .shouldOffload(call.getName(), content)) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolCallId", callKey(call));
        metadata.put("toolName", call.getName());
        AgentArtifactReference reference = artifactStore.save(run.getId(),
            "application/json", content, metadata);
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("offloaded", true);
        placeholder.put("artifactId", reference.getArtifactId());
        placeholder.put("mediaType", reference.getMediaType());
        placeholder.put("size", reference.getSize());
        placeholder.put("checksum", reference.getChecksum());
        result.setContent(JSON.toJSONString(placeholder));
        result.putMetadata("agent.artifact.id", reference.getArtifactId());
        result.putMetadata("agent.artifact.checksum", reference.getChecksum());
        emitRuntime(run, AgentRuntimeEventType.TOOL_RESULT_OFFLOADED,
            objectAttributes("toolCallId", callKey(call), "toolName", call.getName(),
                "artifactId", reference.getArtifactId(), "size", reference.getSize()));
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
    private ToolMessage buildToolRejectedMessage(AgentRun run, ToolCall call,
                                                 ToolApprovalDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("type", "tool_rejected");
        Object resumedReason = run.getMetadata().get("toolRejectionReason." + callKey(call));
        Object approvalAudit = run.getMetadata().get("toolApprovalAudit." + callKey(call));
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
     * 为未提供 ID 的 ToolCall 生成可持久化且在当前 Run 内唯一的关联 ID。
     */
    private void ensureToolCallIds(AgentRun run, AiMessage message) {
        if (message == null || !message.hasToolCalls()) {
            return;
        }
        int index = 0;
        for (ToolCall call : message.getToolCalls()) {
            if (!StringUtil.hasText(call.getId())) {
                call.setId(run.getId() + "-" + run.getIterationCount() + "-" + index);
            }
            index++;
        }
    }

    /**
     * 从消息历史倒序查找最近的 AI 消息，供 FINISHED Phase 或自定义模式完成运行。
     */
    private AiMessage lastAiMessage(AgentRun run) {
        List<Message> messages = run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage) {
                return (AiMessage) messages.get(i);
            }
        }
        return null;
    }

    /**
     * 确保 Run 已装配规划工具，并兼容尚未保存初始 Snapshot 的包内创建路径。
     */
    private void ensurePreparedAndCheckpointed(AgentRun run) {
        prepareRun(run);
        if (run.getVersion() < 0) {
            checkpoint(run);
        }
    }

    /**
     * 解析规划白名单中的完整 Agent，并为当前 Run 装配模型可见的规划工具。
     */
    private void prepareRun(AgentRun run) {
        if (run == null) throw new IllegalArgumentException("run must not be null");
        prepareAgent(run.getAgent());
        if (run.isPlanningToolsPrepared()) return;
        List<Agent> delegates = new ArrayList<>();
        for (String agentId : run.getAgent().getPlanningPolicy().getAllowedAgentIds()) {
            // 这里只解析允许委派的完整 Agent，用于向模型暴露准确描述并在后续创建子 Run。
            Agent delegate = agentLoader.loadActive(agentId);
            if (delegate == null) {
                throw new IllegalStateException(
                    "Allowed planning Agent cannot be loaded: " + agentId);
            }
            delegates.add(delegate);
        }
        run.preparePlanningTools(delegates);
    }

    /**
     * 拒绝非租约持有者推进仍处于有效租约中的 Run。
     *
     * <p>Worker 路径同时校验 owner、唯一 leaseId 和存储端到期时间；同步 API 路径只能推进当前没有
     * 有效 Lease 的 Run。该检查必须位于每个副作用和 Checkpoint 之前，防止过期 Worker 覆盖新状态。</p>
     */
    private void assertLeaseOwnership(AgentRun run) {
        String workerId = activeWorkerId.get();
        if (workerId != null) {
            if (!workerId.equals(run.getLeaseOwner())
                || !StringUtil.hasText(activeLeaseId.get())
                || !activeLeaseId.get().equals(run.getLeaseId())
                || run.getLeaseUntil() <= runStore.currentTimeMillis()) {
                throw new IllegalStateException("AgentRun lease was lost by worker: " + workerId);
            }
            return;
        }
        if (StringUtil.hasText(run.getLeaseOwner())
            && run.getLeaseUntil() > runStore.currentTimeMillis()) {
            throw new IllegalStateException("AgentRun is leased by worker: " + run.getLeaseOwner());
        }
    }

    private void prepareAgent(Agent agent) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
    }

    /**
     * 将具体暂停原因映射为对外可查询的生命周期等待状态。
     */
    private AgentRunStatus blockedStatusFor(AgentSuspensionType type) {
        switch (type) {
            case USER_INPUT:
                return AgentRunStatus.WAITING_FOR_USER;
            case TOOL_APPROVAL:
                return AgentRunStatus.WAITING_FOR_APPROVAL;
            case CHILD_AGENT:
                return AgentRunStatus.WAITING_FOR_CHILD;
            case RETRY:
                return AgentRunStatus.RETRY_SCHEDULED;
            default:
                throw new IllegalStateException("Unsupported suspension type: " + type);
        }
    }

    /**
     * 校验恢复命令与当前 Suspension 匹配，并把命令携带的数据写入 Run。
     *
     * <p>本方法只应用数据，不改变 Status 和 Phase；调用方在校验完成后统一调用 resumeAt 并保存
     * Checkpoint，避免部分应用一个无效命令。</p>
     */
    private void applyResumeCommand(AgentRun run, AgentSuspension suspension,
                                    AgentResumeCommand command) {
        if (suspension == null) {
            throw new IllegalStateException("blocked run has no suspension data");
        }
        switch (suspension.getType()) {
            case USER_INPUT:
                requireCommand(command, AgentResumeCommandType.USER_INPUT);
                if (!StringUtil.hasText(command.getContent())) {
                    throw new IllegalArgumentException("user input must not be blank");
                }
                // 补充信息作为新的用户消息进入现有 Prompt，之前的推理与工具结果全部保留。
                run.getPrompt().addUserMessage(command.getContent());
                break;
            case TOOL_APPROVAL:
                applyToolApproval(run, suspension, command);
                break;
            case RETRY:
                if (command.getType() != AgentResumeCommandType.CONTINUE
                    && command.getType() != AgentResumeCommandType.RETRY) {
                    throw new IllegalArgumentException("CONTINUE or RETRY command is required");
                }
                if (run.getNextRunAt() > System.currentTimeMillis()
                    && command.getType() == AgentResumeCommandType.RETRY) {
                    throw new IllegalStateException("retry is not due yet: " + run.getNextRunAt());
                }
                // RETRY 遵守 nextRunAt；CONTINUE 是显式人工强制继续，可忽略尚未到期的调度时间。
                run.clearRetryError();
                break;
            case CHILD_AGENT:
                requireCommand(command, AgentResumeCommandType.CHILD_COMPLETED);
                requireCorrelation(suspension, command);
                break;
            default:
                throw new IllegalStateException("Unsupported suspension type: " + suspension.getType());
        }
        run.putMetadata("lastResumeCommand", command.getType().name());
        run.putMetadata("lastResumeCorrelationId", command.getCorrelationId());
        if (!command.getMetadata().isEmpty()) {
            run.putMetadata("lastResumeCommandMetadata",
                new LinkedHashMap<String, Object>(command.getMetadata()));
        }
    }

    /**
     * 校验审批命令及 ToolCall 关联 ID，并记录后续工具阶段可直接读取的布尔决定。
     */
    private void applyToolApproval(AgentRun run, AgentSuspension suspension,
                                   AgentResumeCommand command) {
        if (command.getType() != AgentResumeCommandType.APPROVE_TOOL
            && command.getType() != AgentResumeCommandType.REJECT_TOOL) {
            throw new IllegalArgumentException("APPROVE_TOOL or REJECT_TOOL command is required");
        }
        requireCorrelation(suspension, command);
        run.approveTool(suspension.getCorrelationId(),
            command.getType() == AgentResumeCommandType.APPROVE_TOOL);
        run.putMetadata("toolApprovalAudit." + suspension.getCorrelationId(),
            new LinkedHashMap<String, Object>(suspension.getMetadata()));
        if (command.getType() == AgentResumeCommandType.REJECT_TOOL
            && StringUtil.hasText(command.getContent())) {
            run.putMetadata("toolRejectionReason." + suspension.getCorrelationId(), command.getContent());
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

    /*
     * 以下 notify 方法统一发布三种不同用途的观察信号：
     * 1. AgentRunEventStore 保存可跨进程查询的生命周期事件；
     * 2. AgentRuntimeEventStream 服务当前 JVM 的流式 UI 和细粒度追踪；
     * 3. AgentListener 提供直接读取 Java 对象的粗粒度回调。
     *
     * 状态变更类通知通常在对应 Checkpoint 成功后触发，避免观察方看到尚未持久化的状态。
     */
    private void notifyRunStart(AgentRun run) {
        publishEvent(run, AgentRunEventType.RUN_STARTED, null);
        emitRuntime(run, AgentRuntimeEventType.RUN_STARTED, null);
        forEachListener(l -> l.onRunStart(run));
    }

    private void notifyModelStart(AgentRun run) {
        publishEvent(run, AgentRunEventType.MODEL_STARTED,
            iterationAttributes(run));
        emitRuntime(run, AgentRuntimeEventType.MODEL_STARTED,
            objectAttributes("iteration", run.getIterationCount()));
        forEachListener(l -> l.onModelStart(run));
    }

    private void notifyModelEnd(AgentRun run, AiMessageResponse response) {
        Map<String, String> values = iterationAttributes(run);
        values.put("hasToolCalls", String.valueOf(
            response != null && response.getMessage() != null
                && response.getMessage().hasToolCalls()));
        publishEvent(run, AgentRunEventType.MODEL_COMPLETED, values);
        emitRuntime(run, AgentRuntimeEventType.MODEL_COMPLETED,
            objectAttributes("iteration", run.getIterationCount(), "hasToolCalls",
                response != null && response.getMessage() != null
                    && response.getMessage().hasToolCalls()));
        forEachListener(l -> l.onModelEnd(run, response));
    }

    private void notifyToolStart(AgentRun run, ToolCall call) {
        Map<String, String> values = iterationAttributes(run);
        values.putAll(attributes("toolCallId", callKey(call), "toolName", call.getName()));
        publishEvent(run, AgentRunEventType.TOOL_STARTED, values);
        emitRuntime(run, AgentRuntimeEventType.TOOL_STARTED,
            objectAttributes("toolCallId", callKey(call), "toolName", call.getName()));
        forEachListener(l -> l.onToolStart(run, call));
    }

    private void notifyToolEnd(AgentRun run, ToolCall call, ToolMessage message) {
        Map<String, String> values = iterationAttributes(run);
        values.putAll(attributes("toolCallId", callKey(call), "toolName", call.getName()));
        publishEvent(run, AgentRunEventType.TOOL_COMPLETED, values);
        emitRuntime(run, AgentRuntimeEventType.TOOL_COMPLETED,
            objectAttributes("toolCallId", callKey(call), "toolName", call.getName()));
        forEachListener(l -> l.onToolEnd(run, call, message));
    }

    private void notifyToolError(AgentRun run, ToolCall call, Throwable error) {
        publishEvent(run, AgentRunEventType.TOOL_FAILED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "error", errorMessage(error)));
        emitRuntime(run, AgentRuntimeEventType.TOOL_FAILED,
            objectAttributes("toolCallId", callKey(call), "toolName", call.getName(),
                "error", errorMessage(error)));
        forEachListener(l -> l.onToolError(run, call, error));
    }

    private void notifyRunComplete(AgentRun run) {
        publishEvent(run, AgentRunEventType.RUN_COMPLETED, null);
        emitRuntime(run, AgentRuntimeEventType.RUN_COMPLETED, null);
        forEachListener(l -> l.onRunComplete(run));
    }

    private void notifyRunFailed(AgentRun run, Throwable error) {
        publishEvent(run, AgentRunEventType.RUN_FAILED,
            attributes("error", errorMessage(error)));
        emitRuntime(run, AgentRuntimeEventType.RUN_FAILED,
            objectAttributes("error", errorMessage(error)));
        forEachListener(l -> l.onRunFailed(run, error));
    }

    private void notifyRunCancelled(AgentRun run) {
        publishEvent(run, AgentRunEventType.RUN_CANCELLED, null);
        emitRuntime(run, AgentRuntimeEventType.RUN_CANCELLED, null);
        forEachListener(l -> l.onRunCancelled(run));
    }

    private void notifyCancellationRequested(AgentRun run) {
        publishEvent(run, AgentRunEventType.CANCELLATION_REQUESTED, null);
        emitRuntime(run, AgentRuntimeEventType.CANCELLATION_REQUESTED, null);
    }

    private void notifyMaxIterationsReached(AgentRun run) {
        publishEvent(run, AgentRunEventType.MAX_ITERATIONS_REACHED,
            attributes("iterations", String.valueOf(run.getIterationCount())));
        emitRuntime(run, AgentRuntimeEventType.MAX_ITERATIONS_REACHED,
            objectAttributes("iterations", run.getIterationCount()));
        forEachListener(l -> l.onMaxIterationsReached(run));
    }

    private void notifyMaxStepsReached(AgentRun run) {
        publishEvent(run, AgentRunEventType.MAX_STEPS_REACHED,
            attributes("steps", String.valueOf(run.getStepCount()),
                "maxSteps", String.valueOf(run.getExecutionPolicy().getMaxSteps())));
        emitRuntime(run, AgentRuntimeEventType.MAX_STEPS_REACHED,
            objectAttributes("steps", run.getStepCount(),
                "maxSteps", run.getExecutionPolicy().getMaxSteps()));
        forEachListener(l -> l.onMaxStepsReached(run));
    }

    private void notifyCheckpoint(AgentRun run, AgentRunSnapshot snapshot) {
        publishEvent(run, AgentRunEventType.CHECKPOINT_SAVED,
            attributes("version", String.valueOf(snapshot.getVersion()),
                "status", snapshot.getStatus().name(), "phase", snapshot.getPhase().name()));
        emitRuntime(run, AgentRuntimeEventType.CHECKPOINT_SAVED,
            objectAttributes("version", snapshot.getVersion(), "status", snapshot.getStatus(),
                "phase", snapshot.getPhase()));
        forEachListener(l -> l.onCheckpoint(run, snapshot));
    }

    private void notifyRunSuspended(AgentRun run, AgentSuspension suspension) {
        publishEvent(run, AgentRunEventType.RUN_SUSPENDED,
            attributes("suspensionType", suspension.getType().name(),
                "correlationId", suspension.getCorrelationId()));
        emitRuntime(run, AgentRuntimeEventType.RUN_SUSPENDED,
            objectAttributes("suspensionType", suspension.getType(),
                "correlationId", suspension.getCorrelationId()));
        forEachListener(l -> l.onRunSuspended(run, suspension));
    }

    private void notifyRunResumed(AgentRun run, AgentResumeCommand command) {
        publishEvent(run, AgentRunEventType.RUN_RESUMED,
            attributes("commandType", command.getType().name(),
                "correlationId", command.getCorrelationId()));
        emitRuntime(run, AgentRuntimeEventType.RUN_RESUMED,
            objectAttributes("commandType", command.getType(),
                "correlationId", command.getCorrelationId()));
        forEachListener(l -> l.onRunResumed(run, command));
    }

    private void notifyToolApprovalRequested(AgentRun run, ToolCall call,
                                             ToolApprovalDecision decision) {
        publishEvent(run, AgentRunEventType.TOOL_APPROVAL_REQUESTED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "approvalCode", decision.getCode(), "approvalReason", decision.getReason()));
        emitRuntime(run, AgentRuntimeEventType.TOOL_APPROVAL_REQUESTED,
            objectAttributes("toolCallId", callKey(call), "toolName", call.getName(),
                "decision", decision));
        forEachListener(l -> l.onToolApprovalRequested(run, call));
    }

    private void notifyRetryScheduled(AgentRun run, Throwable error) {
        publishEvent(run, AgentRunEventType.RETRY_SCHEDULED,
            attributes("retryCount", String.valueOf(run.getRetryCount()),
                "nextRunAt", String.valueOf(run.getNextRunAt()),
                "error", errorMessage(error)));
        emitRuntime(run, AgentRuntimeEventType.RETRY_SCHEDULED,
            objectAttributes("retryCount", run.getRetryCount(),
                "nextRunAt", run.getNextRunAt(), "error", errorMessage(error)));
        forEachListener(l -> l.onRetryScheduled(run, error));
    }

    private void notifyBudgetExceeded(AgentRun run, String reason) {
        publishEvent(run, AgentRunEventType.BUDGET_EXCEEDED,
            attributes("reason", reason));
        emitRuntime(run, AgentRuntimeEventType.BUDGET_EXCEEDED,
            objectAttributes("reason", reason));
        forEachListener(l -> l.onBudgetExceeded(run, reason));
    }

    private void notifyChildStarted(AgentRun parent, AgentRun child) {
        publishEvent(parent, AgentRunEventType.CHILD_STARTED,
            attributes("childRunId", child.getId(), "childAgentId", child.getAgent().getId()));
        emitRuntime(parent, AgentRuntimeEventType.CHILD_STARTED,
            objectAttributes("childRunId", child.getId(),
                "childAgentId", child.getAgent().getId()));
        forEachListener(l -> l.onChildStarted(parent, child));
    }

    /**
     * 发布模型已经创建任务计划的持久化事件和实时事件。
     */
    private void notifyPlanCreated(AgentRun run, AgentTaskPlan plan) {
        publishEvent(run, AgentRunEventType.PLAN_CREATED,
            attributes("planId", plan.getId(), "taskCount",
                String.valueOf(plan.getTasks().size())));
        emitRuntime(run, AgentRuntimeEventType.PLAN_CREATED,
            objectAttributes("planId", plan.getId(), "goal", plan.getGoal(),
                "taskCount", plan.getTasks().size()));
    }

    /**
     * 发布模型已经调整待执行任务的持久化事件和实时事件。
     */
    private void notifyPlanUpdated(AgentRun run, AgentTaskPlan plan) {
        publishEvent(run, AgentRunEventType.PLAN_UPDATED,
            attributes("planId", plan.getId(),
                "revisionCount", String.valueOf(plan.getRevisionCount()),
                "reason", plan.getLastRevisionReason(),
                "taskCount", String.valueOf(plan.getTasks().size())));
        emitRuntime(run, AgentRuntimeEventType.PLAN_UPDATED,
            objectAttributes("planId", plan.getId(),
                "revisionCount", plan.getRevisionCount(),
                "reason", plan.getLastRevisionReason(),
                "taskCount", plan.getTasks().size()));
    }

    /**
     * 发布任务已经绑定子 Run 并开始执行的事件。
     */
    private void notifyTaskStarted(AgentRun parent, AgentTask task, AgentRun child) {
        publishEvent(parent, AgentRunEventType.TASK_STARTED,
            attributes("taskId", task.getId(), "childRunId", child.getId(),
                "childAgentId", child.getAgent().getId()));
        emitRuntime(parent, AgentRuntimeEventType.TASK_STARTED,
            objectAttributes("taskId", task.getId(), "title", task.getTitle(),
                "childRunId", child.getId(), "childAgentId", child.getAgent().getId()));
    }

    /**
     * 发布子 Run 已经转换为任务最终状态的事件。
     */
    private void notifyTaskFinished(AgentRun parent, AgentTask task, AgentRun child,
                                    AgentTaskStatus status) {
        AgentRunEventType storedType = status == AgentTaskStatus.COMPLETED
            ? AgentRunEventType.TASK_COMPLETED : AgentRunEventType.TASK_FAILED;
        AgentRuntimeEventType runtimeType = status == AgentTaskStatus.COMPLETED
            ? AgentRuntimeEventType.TASK_COMPLETED : AgentRuntimeEventType.TASK_FAILED;
        publishEvent(parent, storedType,
            attributes("taskId", task.getId(), "childRunId", child.getId(),
                "taskStatus", status.name()));
        emitRuntime(parent, runtimeType,
            objectAttributes("taskId", task.getId(), "childRunId", child.getId(),
                "taskStatus", status, "result", child.getFinalOutput(),
                "error", errorMessage(child.getError())));
    }

    /**
     * 将生命周期事件追加到持久化事件流。
     *
     * <p>基础字段由 Runner 统一生成，Enricher 用于补充租户、账号和业务模块等平台维度，当前事件
     * 自身的 attributes 最后写入。Enricher 异常会被隔离；EventStore 写入异常则向上抛出，因为
     * 配置为持久化事件的应用通常要求事件与执行边界具有一致的失败可见性。</p>
     */
    private void publishEvent(AgentRun run, AgentRunEventType type,
                              Map<String, String> attributes) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("agentId", run.getAgent().getId());
        values.put("agentVersion", run.getAgent().getVersion());
        values.put("executionModeId", run.getAgent().getExecutionMode().getId());
        values.put("executionModeVersion", run.getAgent().getExecutionMode().getVersion());
        values.put("status", run.getStatus().name());
        values.put("stepCount", String.valueOf(run.getStepCount()));
        values.put("maxSteps", String.valueOf(run.getExecutionPolicy().getMaxSteps()));
        for (AgentRunEventEnricher enricher : eventEnrichers) {
            try {
                Map<String, String> enriched = enricher.enrich(run, type);
                if (enriched != null) {
                    values.putAll(enriched);
                }
            } catch (RuntimeException error) {
                log.warn("Agent event enricher failed", error);
            }
        }
        if (attributes != null) {
            // 事件自身字段优先级最高，避免增强器覆盖 iteration、toolCallId 等协议属性。
            values.putAll(attributes);
        }
        eventStore.append(AgentRunEvent.create(run.getId(), type, values));
    }

    /**
     * 生成模型迭代事件共用的当前次数、上限和剩余次数。
     */
    private Map<String, String> iterationAttributes(AgentRun run) {
        int maxIterations = run.getExecutionPolicy().getMaxIterations();
        return attributes("iteration", String.valueOf(run.getIterationCount()),
            "maxIterations", String.valueOf(maxIterations),
            "remainingIterations", String.valueOf(
                Math.max(0, maxIterations - run.getIterationCount())));
    }

    /**
     * 过滤空值并创建事件属性。
     */
    private Map<String, String> attributes(String... values) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i + 1] != null) {
                attributes.put(values[i], values[i + 1]);
            }
        }
        return attributes;
    }

    /**
     * 创建允许任意数据类型的实时事件属性。
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
     * 把工具主动上报的进度数据合并到框架生成的基础属性中。
     */
    private Map<String, Object> mergeObjectAttributes(Map<String, Object> base,
                                                      Map<String, ?> additions) {
        if (additions != null) base.putAll(additions);
        return base;
    }

    /**
     * 发布仅在当前进程存活期间有效的实时事件；空 Run 不产生事件。
     */
    private void emitRuntime(AgentRun run, AgentRuntimeEventType type, Map<String, ?> data) {
        if (run != null) runtimeEventStream.publish(run, type, data);
    }

    /**
     * 使用稳定的“异常类名 + 消息”格式写入事件，避免持久化 Throwable 对象。
     */
    private String errorMessage(Throwable error) {
        return error == null ? null : error.getClass().getName() + ": " + error.getMessage();
    }

    /**
     * 将 Store 中的单调取消信号同步到当前内存 Run。
     */
    private void refreshCancellation(AgentRun run) {
        if (!run.isCancellationRequested()
            && runStore.isCancellationRequested(run.getId())) {
            run.requestCancellation();
        }
    }

    /**
     * 同步调用所有生命周期监听器，并隔离单个监听器的运行时异常。
     */
    private void forEachListener(ListenerCallback callback) {
        for (AgentListener listener : listeners) {
            try {
                callback.call(listener);
            } catch (RuntimeException error) {
                log.warn("Agent listener failed", error);
            }
        }
    }

    /**
     * 封装一次监听器回调，使统一分发逻辑能够隔离单个监听器抛出的异常。
     */
    private interface ListenerCallback {
        void call(AgentListener listener);
    }

    private static final class AgentRunCancelledException extends RuntimeException {
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

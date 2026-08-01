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
 * 按模型原生 ToolCall 协议推进 AgentRun 的执行器。
 *
 * <p>模型调用和工具调用都在稳定状态边界保存 Checkpoint。模型产生 ToolCall 后，Runner 会先
 * 保存待执行调用，再逐个完成审批、执行和结果写入，从而支持进程退出后的继续执行。</p>
 *
 * <p>Runner 同时负责预算检查、自动重试、暂停恢复、任务规划、父子 Run 协调和生命周期事件。
 * Agent 保存静态能力，AgentRun 保存单次可变状态，所有需要跨进程恢复的变化最终通过
 * AgentRunStore 持久化。Runner 本身不保存任务状态，可以在多个请求之间复用。</p>
 *
 * <p>直接调用 {@code run(...)} 会在当前线程推进子 Run；分布式长任务应先调用 {@code start(...)}
 * 保存 READY Checkpoint，再由 AgentWorker 通过租约领取。不要让两个线程直接推进同一个
 * AgentRun 对象。</p>
 */
public final class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** 保存 Checkpoint、取消标记和 Worker 租约的运行存储。 */
    private final AgentRunStore runStore;
    /** 创建新任务和恢复旧任务时解析完整 Agent 的加载器。 */
    private final AgentLoader agentLoader;
    /** 保存可断点续读生命周期事件的追加式存储。 */
    private final AgentRunEventStore eventStore;
    /** 保存审批、用户输入等外部恢复命令的持久化收件箱。 */
    private final AgentRunCommandStore commandStore;
    /** 保存从 Prompt 卸载的大型工具结果。 */
    private final AgentArtifactStore artifactStore;
    /** 当前 JVM 内用于流式 UI 和低延迟追踪的实时事件流。 */
    private final AgentRuntimeEventStream runtimeEventStream = new AgentRuntimeEventStream();
    /** 统一模型调用、Token 统计和流式事件发布的适配器。 */
    private final AgentModelInvoker modelInvoker = new AgentModelInvoker(runtimeEventStream);
    /** 兼容粗粒度生命周期回调的线程安全监听器列表。 */
    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();
    /** 为持久化事件补充平台审计字段的增强器列表。 */
    private final List<AgentRunEventEnricher> eventEnrichers = new CopyOnWriteArrayList<>();
    /** 命令成功入箱后通知外部调度系统的监听器列表。 */
    private final List<AgentWakeupListener> wakeupListeners = new CopyOnWriteArrayList<>();
    /** 标识当前线程正在代表哪个 Worker 推进已领取的 Run。 */
    private final ThreadLocal<String> activeWorkerId = new ThreadLocal<>();
    private final ThreadLocal<String> activeLeaseId = new ThreadLocal<>();

    /** 创建全部使用进程内依赖的 Runner，适合测试和单实例试用。 */
    public AgentRunner() {
        this(new InMemoryAgentRunStore(), new InMemoryAgentLoader());
    }

    /** 创建可按需替换 Store 和 Loader 的 Runner 构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 创建自定义 RunStore 和 AgentLoader、其余依赖使用进程内实现的 Runner。 */
    public AgentRunner(AgentRunStore runStore, AgentLoader agentLoader) {
        this(runStore, agentLoader, new InMemoryAgentRunEventStore(),
            new InMemoryAgentRunCommandStore(),
            new InMemoryAgentArtifactStore());
    }

    /** 创建额外使用指定持久化事件 Store 的 Runner。 */
    public AgentRunner(AgentRunStore runStore, AgentLoader agentLoader,
                       AgentRunEventStore eventStore) {
        this(runStore, agentLoader, eventStore,
            new InMemoryAgentRunCommandStore(), new InMemoryAgentArtifactStore());
    }

    /** 创建显式提供全部可替换持久化依赖的 Runner。 */
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

        /** 设置 Checkpoint 与租约存储。 */
        public Builder runStore(AgentRunStore value) { runStore = value; return this; }
        /** 设置完整 Agent 加载器。 */
        public Builder agentLoader(AgentLoader value) { agentLoader = value; return this; }
        /** 设置持久化生命周期事件存储。 */
        public Builder eventStore(AgentRunEventStore value) { eventStore = value; return this; }
        /** 设置持久化恢复命令收件箱。 */
        public Builder commandStore(AgentRunCommandStore value) { commandStore = value; return this; }
        /** 设置大型工具结果存储。 */
        public Builder artifactStore(AgentArtifactStore value) { artifactStore = value; return this; }

        /** 校验全部依赖并创建 Runner。 */
        public AgentRunner build() {
            return new AgentRunner(runStore, agentLoader, eventStore,
                commandStore, artifactStore);
        }
    }

    /** @return Runner 使用的 RunStore */
    public AgentRunStore getRunStore() { return runStore; }
    /** @return Runner 使用的 AgentLoader */
    public AgentLoader getAgentLoader() { return agentLoader; }
    /** @return Runner 使用的持久化 EventStore */
    public AgentRunEventStore getEventStore() { return eventStore; }
    /** @return Runner 使用的恢复命令 Store */
    public AgentRunCommandStore getCommandStore() { return commandStore; }
    /** @return Runner 使用的 Artifact Store */
    public AgentArtifactStore getArtifactStore() { return artifactStore; }
    /** @return 当前 Runner 独享的进程内实时事件流 */
    public AgentRuntimeEventStream getRuntimeEventStream() { return runtimeEventStream; }

    /** 添加实时事件监听器并返回当前 Runner 以支持链式配置。 */
    public AgentRunner addRuntimeEventListener(AgentRuntimeEventListener listener) {
        runtimeEventStream.addListener(listener);
        return this;
    }

    /** 添加命令入箱后的调度唤醒监听器。 */
    public AgentRunner addWakeupListener(AgentWakeupListener listener) {
        if (listener != null) wakeupListeners.add(listener);
        return this;
    }

    /** 添加粗粒度 Agent 生命周期监听器。 */
    public AgentRunner addListener(AgentListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /** 添加持久化事件属性增强器，用于写入租户、账号、模块和配置版本等审计维度。 */
    public AgentRunner addEventEnricher(AgentRunEventEnricher enricher) {
        if (enricher != null) {
            eventEnrichers.add(enricher);
        }
        return this;
    }

    /** 创建 Run 并同步推进到终止或阻塞状态。 */
    public AgentRun run(Agent agent, String userInput) {
        return run(start(agent, userInput));
    }

    /** 使用单次运行策略和元数据创建并执行任务。 */
    public AgentRun run(Agent agent, String userInput, AgentRunOptions options) {
        return run(start(agent, userInput, options));
    }

    /** 使用一条可包含文本、图片、音频、视频和文件的用户消息执行 Run。 */
    public AgentRun run(Agent agent, UserMessage userMessage) {
        return run(start(agent, userMessage));
    }

    /** 使用结构化用户消息和单次运行选项执行 Run。 */
    public AgentRun run(Agent agent, UserMessage userMessage, AgentRunOptions options) {
        return run(start(agent, userMessage, options));
    }

    /** 使用已有会话历史和本轮结构化消息创建并执行新的 Run。 */
    public AgentRun run(Agent agent, List<? extends Message> conversationHistory,
                        UserMessage userMessage) {
        return run(start(agent, conversationHistory, userMessage));
    }

    /** 使用已有会话历史、本轮结构化消息和单次运行选项创建并执行新的 Run。 */
    public AgentRun run(Agent agent, List<? extends Message> conversationHistory,
                        UserMessage userMessage, AgentRunOptions options) {
        return run(start(agent, conversationHistory, userMessage, options));
    }

    /** 在持续对话中使用本轮结构化消息创建并执行新的 Run。 */
    public AgentRun run(AgentConversation conversation, UserMessage userMessage) {
        return run(start(conversation, userMessage));
    }

    /** 在持续对话中使用纯文本便捷创建并执行新的 Run。 */
    public AgentRun run(AgentConversation conversation, String userInput) {
        return run(conversation, new UserMessage(userInput));
    }

    /** 在持续对话中使用本轮结构化消息和运行时覆盖参数创建并执行新的 Run。 */
    public AgentRun run(AgentConversation conversation, UserMessage userMessage,
                        AgentRunOptions options) {
        return run(start(conversation, userMessage, options));
    }

    /** 创建并保存一个尚未执行的 Run。 */
    public AgentRun start(Agent agent, String userInput) {
        return start(agent, userInput, AgentRunOptions.defaults());
    }

    /** 创建并保存一个带有运行时覆盖参数的任务。 */
    public AgentRun start(Agent agent, String userInput, AgentRunOptions options) {
        return start(agent, new UserMessage(userInput), options);
    }

    /** 创建并保存一个使用结构化用户消息、尚未执行的 Run。 */
    public AgentRun start(Agent agent, UserMessage userMessage) {
        return start(agent, userMessage, AgentRunOptions.defaults());
    }

    /** 创建并保存一个使用结构化用户消息和运行时覆盖参数的 Run。 */
    public AgentRun start(Agent agent, UserMessage userMessage, AgentRunOptions options) {
        return start(agent, Collections.<Message>emptyList(), userMessage, options);
    }

    /** 使用已有会话历史和本轮结构化消息创建并保存新的 Run。 */
    public AgentRun start(Agent agent, List<? extends Message> conversationHistory,
                          UserMessage userMessage) {
        return start(agent, conversationHistory, userMessage, AgentRunOptions.defaults());
    }

    /** 使用已有会话历史、本轮结构化消息和运行时覆盖参数创建并保存新的 Run。 */
    public AgentRun start(Agent agent, List<? extends Message> conversationHistory,
                          UserMessage userMessage, AgentRunOptions options) {
        prepareAgent(agent);
        AgentRun run = AgentRun.start(agent, conversationHistory, userMessage, options);
        prepareRun(run);
        checkpoint(run);
        return run;
    }

    /** 在持续对话中创建并保存一个尚未执行的 Run。 */
    public AgentRun start(AgentConversation conversation, UserMessage userMessage) {
        return start(conversation, userMessage, AgentRunOptions.defaults());
    }

    /** 在持续对话中使用纯文本便捷创建并保存新的 Run。 */
    public AgentRun start(AgentConversation conversation, String userInput) {
        return start(conversation, new UserMessage(userInput));
    }

    /** 在持续对话中创建并保存一个带有运行时覆盖参数的 Run。 */
    public AgentRun start(AgentConversation conversation, UserMessage userMessage,
                          AgentRunOptions options) {
        if (conversation == null) {
            throw new IllegalArgumentException("conversation must not be null");
        }
        synchronized (conversation) {
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
                conversation.release(run.getId());
                replaceConversationMemory(conversation, previousMessages);
                throw error;
            }
        }
    }

    /** 推进已经创建的 Run，语义等同于 {@link #runUntilBlocked(AgentRun)}。 */
    public AgentRun run(AgentRun run) { return runUntilBlocked(run); }

    /**
     * 持续推进，直到根任务终止或等待外部事件。
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
            while (!current.getStatus().isTerminal()
                && (!current.getStatus().isBlocked() || current.isCancellationRequested())) {
                step(current);
            }
            AgentRun child = currentPlannedChild(current);
            if (child == null || current.isCancellationRequested()) return current;
            // Worker 只能推进自己通过 Store 领取的 Run，子 Run 必须另行领取并获得独立租约。
            if (activeWorkerId.get() != null) return current;
            child = runUntilBlocked(child);
            if (!child.getStatus().isTerminal()) return current;
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
        AgentRunSnapshot snapshot = runStore.load(runId);
        if (snapshot == null) {
            throw new IllegalStateException("AgentRun checkpoint not found: " + runId);
        }
        Agent agent = agentLoader.load(snapshot.getAgentId(), snapshot.getAgentVersion());
        if (agent == null) {
            throw new IllegalStateException("Agent cannot be loaded: " + snapshot.getAgentId()
                + ", version=" + snapshot.getAgentVersion());
        }
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

    /** 从最新 Checkpoint 恢复指定 Run 并推进到下一个稳定边界。 */
    public AgentRun runUntilBlocked(String runId) {
        return runUntilBlocked(restore(runId));
    }

    /** 将 Run 置为等待外部事件的状态并立即保存。 */
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
        applyResumeCommand(run, suspension, command);
        if (commandId != null) {
            run.putMetadata(processedCommandKey(commandId), true);
        }
        run.resumeAt(suspension.getResumePhase());
        checkpoint(run);
        notifyRunResumed(run, command);
        return run;
    }

    /** 恢复指定 ID 的阻塞 Run，并同步推进到下一个稳定边界。 */
    public AgentRun resume(String runId, AgentResumeCommand command) {
        return resume(restore(runId), command);
    }

    /** 恢复持续对话中正在等待外部输入的 Run。 */
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

    /** 将恢复命令持久化到收件箱，并在保存成功后发送唤醒通知。 */
    public AgentRunCommand submitCommand(String runId, AgentResumeCommand command) {
        return submitCommand(UUID.randomUUID().toString(), runId, command);
    }

    /** 使用调用方提供的命令 ID 幂等提交恢复命令。 */
    public AgentRunCommand submitCommand(String commandId, String runId,
                                         AgentResumeCommand command) {
        AgentRunCommand existing = commandStore.load(commandId);
        if (existing != null) {
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
                commandStore.acknowledge(item.getCommandId(), workerId);
                emitRuntime(run, AgentRuntimeEventType.COMMAND_CONSUMED,
                    objectAttributes("commandId", item.getCommandId()));
                completed++;
            } catch (RuntimeException error) {
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
     */
    public AgentRunSnapshot checkpoint(AgentRun run) {
        synchronized (run) {
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
     * 推进当前模型或工具步骤。一次调用最多调用模型一次，但可顺序处理该模型回合产生的全部工具调用。
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

    /** Run 结束时允许 Conversation 接收下一轮消息；阻塞和运行状态继续保留活动 Run。 */
    private void updateConversation(AgentRun run) {
        if (run == null || run.getConversation() == null) {
            return;
        }
        if (run.getStatus().isTerminal()) {
            replaceConversationMemory(run.getConversation(), run.getConversationHistory());
            run.getConversation().release(run.getId());
        }
    }

    /** 使用 Checkpoint 消息重建 Conversation Memory，并排除由 Agent 定义重新注入的系统消息。 */
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

    private AgentStepResult stepCore(AgentRun run) {
        validateStep(run);
        ensurePreparedAndCheckpointed(run);
        assertLeaseOwnership(run);
        refreshCancellation(run);
        if (run.isCancellationRequested()) {
            return cancelRun(run);
        }
        if (run.getStatus().isBlocked()) {
            return AgentStepResult.of(AgentStepType.BLOCKED, null, null, null);
        }
        if (run.markStarted()) {
            notifyRunStart(run);
        }
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

        AgentStepResult planningResult = advanceTaskPlan(run);
        if (planningResult != null) return planningResult;

        AgentStepResult result = proceedStep(run,
            new AgentMiddlewareContext(this, run, run.getPrompt()), 0);
        if (result == null) {
            return handleFailure(run, null,
                new IllegalStateException("AgentExecutionMode returned null step result"),
                run.getPhase());
        }
        refreshCancellation(run);
        if (run.isCancellationRequested() && !run.getStatus().isTerminal()) {
            return cancelRun(run);
        }
        return result;
    }

    /**
     * 供默认模式及组合模式调用的模型原生 ToolCall 单步执行方法。
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

    /** 供自定义模式正常结束 Run。 */
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

    /** 供自定义模式按统一重试和失败策略处理异常。 */
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
        assertLeaseOwnership(parent);
        Agent childAgent = agentLoader.loadActive(childAgentId);
        if (childAgent == null) {
            throw new IllegalStateException("Active child agent cannot be loaded: " + childAgentId);
        }
        prepareAgent(childAgent);
        AgentRun child = AgentRun.startChild(childAgent, input, parent);
        prepareRun(child);
        AgentTaskPlan plan = parent.getTaskPlan();
        if (plan != null && plan.getActiveTask() == null && plan.getNextTask() != null) {
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
        if (parent.getStatus() != AgentRunStatus.WAITING_FOR_CHILD || suspension == null
            || !child.getId().equals(suspension.getCorrelationId())) {
            return parent;
        }
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
        return submitResume(parent, AgentResumeCommand.childCompleted(child.getId()));
    }

    /** 查询根 Run 中的计划以及当前子 Run 的真实阻塞状态。 */
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

    /** 在指定 Worker 的租约上下文中推进已经领取的 Run。 */
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

    private AgentStepResult executeModel(AgentRun run) {
        Agent agent = run.getAgent();
        if (run.getIterationCount() >= run.getExecutionPolicy().getMaxIterations()) {
            run.markMaxIterationsReached();
            checkpoint(run);
            notifyMaxIterationsReached(run);
            return AgentStepResult.of(AgentStepType.MAX_ITERATIONS_REACHED,
                null, null, null);
        }

        run.incrementIteration();
        notifyModelStart(run);
        AiMessageResponse response;
        try {
            AgentContextUpdate update = agent.getContextManager()
                .prepare(run, run.getInvocationContext());
            if (update != null && update.isChanged()) {
                checkpoint(run);
                emitRuntime(run, AgentRuntimeEventType.CONTEXT_COMPACTED,
                    objectAttributes("removedMessageCount", update.getRemovedMessageCount(),
                        "remainingMessageCount", update.getRemainingMessageCount()));
            }
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
        ensureToolCallIds(run, message);
        run.addUsage(message);
        run.getPrompt().addMessage(message);
        String budgetReason = budgetExceededReason(run, false);
        if (budgetReason != null) {
            return budgetExceeded(run, budgetReason);
        }

        if (!message.hasToolCalls()) {
            return complete(run, response, message);
        }

        run.setPendingToolCalls(message.getToolCalls());
        run.moveTo(AgentRunPhase.TOOLS);
        checkpoint(run);
        return executePendingTools(run, response);
    }

    private AiMessageResponse proceedModelCall(AgentRun run,
                                               AgentMiddlewareContext context, int index) {
        List<AgentMiddleware> middlewares = run.getAgent().getMiddlewares();
        if (index >= middlewares.size()) return invokeModel(run, context.getPrompt());
        AgentMiddleware middleware = middlewares.get(index);
        AgentModelCallChain chain = next -> proceedModelCall(run, next, index + 1);
        return middleware.aroundModelCall(context, chain);
    }

    private AiMessageResponse invokeModel(AgentRun run, Prompt prompt) {
        return modelInvoker.invoke(run, prompt);
    }

    /** 逐个处理待执行 ToolCall，并在每个结果写入后保存 Checkpoint。 */
    private AgentStepResult executePendingTools(AgentRun run, AiMessageResponse response) {
        List<ToolMessage> results = new ArrayList<>();
        while (!run.getPendingToolCalls().isEmpty()) {
            if (run.isCancellationRequested()) {
                return cancelRun(run);
            }
            String budgetReason = budgetExceededReason(run, true);
            if (budgetReason != null) {
                return budgetExceeded(run, budgetReason);
            }

            ToolCall call = run.getPendingToolCalls().get(0);
            if (AgentPlanningTool.NAME.equals(call.getName())
                || AgentPlanningTool.UPDATE_NAME.equals(call.getName())) {
                try {
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
                return handleFailure(run, response,
                    new AgentToolNotFoundException(call.getName()), AgentRunPhase.TOOLS);
            }

            Boolean approval = run.getToolApproval(callKey(call));
            ToolApprovalDecision decision = approval == null
                ? run.getAgent().getToolApprovalPolicy().decide(run, call, tool)
                : (approval ? ToolApprovalDecision.ALLOW : ToolApprovalDecision.DENY);
            if (decision == null) {
                return handleFailure(run, response,
                    new IllegalStateException("ToolApprovalPolicy returned null"), AgentRunPhase.TOOLS);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.REQUIRE_APPROVAL) {
                AgentSuspension suspension = AgentSuspension.toolApproval(
                    callKey(call), call.getName(), decision);
                suspend(run, suspension);
                notifyToolApprovalRequested(run, call, decision);
                return AgentStepResult.of(AgentStepType.BLOCKED, response, results, null);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.DENY) {
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

        run.moveTo(AgentRunPhase.MODEL);
        checkpoint(run);
        return AgentStepResult.of(AgentStepType.TOOLS_EXECUTED, response, results, null);
    }

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

    /** 校验并应用模型对尚未执行任务的调整。 */
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

    /** 将规划工具中的任务数组转换为经过委派白名单校验的任务定义。 */
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

    /** 在普通 step 循环中调度计划的下一任务，或进入最终汇总阶段。 */
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
        startChild(run, agentId, taskInput(plan, next));
        return AgentStepResult.of(AgentStepType.BLOCKED, null, null, null);
    }

    /** 返回计划当前关联的子 Run；不存在活动计划任务时返回 null。 */
    private AgentRun currentPlannedChild(AgentRun parent) {
        if (parent == null || parent.getStatus() != AgentRunStatus.WAITING_FOR_CHILD) return null;
        AgentTaskPlan plan = parent.getTaskPlan();
        AgentTask task = plan == null ? null : plan.getActiveTask();
        return task == null || !StringUtil.hasText(task.getChildRunId())
            ? null : restore(task.getChildRunId(), parent.getInvocationContext());
    }

    /** 校验模型选择的目标 Agent 满足当前 Agent 的委派约束。 */
    private void validateTaskAgent(AgentRun parent, AgentTask task, String childAgentId) {
        String expected = StringUtil.hasText(task.getAssignedAgentId())
            ? task.getAssignedAgentId() : parent.getAgent().getId();
        if (!expected.equals(childAgentId)
            || !parent.getAgent().getPlanningPolicy()
                .canDelegateTo(parent.getAgent().getId(), childAgentId)) {
            throw new IllegalArgumentException("task cannot be delegated to Agent: " + childAgentId);
        }
    }

    /** 生成只包含总体目标和当前任务的子 Run 输入。 */
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

    /** 判断失败后的计划是否仍有一次受策略约束的调整机会。 */
    private boolean canReplan(AgentPlanningPolicy policy, AgentTaskPlan plan) {
        return policy.getMaxReplans() > plan.getRevisionCount()
            && (policy.isTaskRevisionAllowed() || policy.isTaskAppendAllowed())
            && plan.getNextTask() != null;
    }

    /** 限制复制到父计划和父提示词中的子任务结果长度，子 Run 原始结果保持不变。 */
    private String limitTaskResult(AgentRun parent, String value) {
        if (value == null) return null;
        int maxLength = parent.getAgent().getPlanningPolicy().getTaskResultMaxLength();
        if (maxLength <= 0 || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "\n[子任务结果已截断，完整内容保留在子 Run 中]";
    }

    private Tool resolveTool(AgentRun run, ToolCall call) {
        return call == null ? null : run.getAgent().getTool(call.getName());
    }

    private AgentStepResult handleFailure(AgentRun run, AiMessageResponse response,
                                          RuntimeException error, AgentRunPhase resumePhase) {
        if (error instanceof AgentRunCancelledException || run.isCancellationRequested()) {
            return cancelRun(run);
        }
        AgentRetryPolicy retry = run.getExecutionPolicy().getRetryPolicy();
        if (isRetryable(error) && run.getRetryCount() < retry.getMaxRetries()) {
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

    private boolean isRetryable(RuntimeException error) {
        return !(error instanceof AgentToolNotFoundException)
            && !(error instanceof IllegalArgumentException);
    }

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

    private AgentStepResult cancelRun(AgentRun run) {
        run.markCancelled();
        checkpoint(run);
        notifyRunCancelled(run);
        return AgentStepResult.of(AgentStepType.CANCELLED, null, null, null);
    }

    private AgentStepResult budgetExceeded(AgentRun run, String reason) {
        run.markBudgetExceeded(reason);
        checkpoint(run);
        notifyBudgetExceeded(run, reason);
        return AgentStepResult.of(AgentStepType.BUDGET_EXCEEDED,
            null, null, null);
    }

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

    private ToolMessage executeTool(AgentRun run, Tool tool, ToolCall call) {
        List<ToolInterceptor> interceptors = run.getAgent().getToolInterceptors();
        AgentToolInvocation invocation = new AgentToolInvocation(
            run.getId(), run.getRootRunId(), run.getParentRunId(),
            run.getAgent().getId(), run.getAgent().getVersion(), callKey(call), tool.getName());
        AgentToolCallContext middlewareContext = new AgentToolCallContext(this, run, tool, call);
        Object value = proceedToolCall(run, middlewareContext, 0, invocation, interceptors);
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

    private Object proceedToolCall(AgentRun run, AgentToolCallContext context, int index,
                                   AgentToolInvocation invocation,
                                   List<ToolInterceptor> interceptors) {
        List<AgentMiddleware> middlewares = run.getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
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

    /** 将超过策略阈值的工具结果保存到 Artifact Store，并用稳定引用替换消息正文。 */
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

    private String callKey(ToolCall call) {
        return StringUtil.hasText(call.getId()) ? call.getId() : call.getName();
    }

    /** 为未提供 ID 的 ToolCall 生成可持久化且在当前 Run 内唯一的关联 ID。 */
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

    private AiMessage lastAiMessage(AgentRun run) {
        List<Message> messages = run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage) {
                return (AiMessage) messages.get(i);
            }
        }
        return null;
    }

    private void ensurePreparedAndCheckpointed(AgentRun run) {
        prepareRun(run);
        if (run.getVersion() < 0) {
            checkpoint(run);
        }
    }

    /** 解析规划白名单中的完整 Agent，并为当前 Run 装配模型可见的规划工具。 */
    private void prepareRun(AgentRun run) {
        if (run == null) throw new IllegalArgumentException("run must not be null");
        prepareAgent(run.getAgent());
        if (run.isPlanningToolsPrepared()) return;
        List<Agent> delegates = new ArrayList<>();
        for (String agentId : run.getAgent().getPlanningPolicy().getAllowedAgentIds()) {
            Agent delegate = agentLoader.loadActive(agentId);
            if (delegate == null) {
                throw new IllegalStateException(
                    "Allowed planning Agent cannot be loaded: " + agentId);
            }
            delegates.add(delegate);
        }
        run.preparePlanningTools(delegates);
    }

    /** 拒绝非租约持有者推进仍处于有效租约中的 Run。 */
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

    private AgentRunStatus blockedStatusFor(AgentSuspensionType type) {
        switch (type) {
            case USER_INPUT: return AgentRunStatus.WAITING_FOR_USER;
            case TOOL_APPROVAL: return AgentRunStatus.WAITING_FOR_APPROVAL;
            case CHILD_AGENT: return AgentRunStatus.WAITING_FOR_CHILD;
            case RETRY: return AgentRunStatus.RETRY_SCHEDULED;
            default: throw new IllegalStateException("Unsupported suspension type: " + type);
        }
    }

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

    /** 发布模型已经创建任务计划的持久化事件和实时事件。 */
    private void notifyPlanCreated(AgentRun run, AgentTaskPlan plan) {
        publishEvent(run, AgentRunEventType.PLAN_CREATED,
            attributes("planId", plan.getId(), "taskCount",
                String.valueOf(plan.getTasks().size())));
        emitRuntime(run, AgentRuntimeEventType.PLAN_CREATED,
            objectAttributes("planId", plan.getId(), "goal", plan.getGoal(),
                "taskCount", plan.getTasks().size()));
    }

    /** 发布模型已经调整待执行任务的持久化事件和实时事件。 */
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

    /** 发布任务已经绑定子 Run 并开始执行的事件。 */
    private void notifyTaskStarted(AgentRun parent, AgentTask task, AgentRun child) {
        publishEvent(parent, AgentRunEventType.TASK_STARTED,
            attributes("taskId", task.getId(), "childRunId", child.getId(),
                "childAgentId", child.getAgent().getId()));
        emitRuntime(parent, AgentRuntimeEventType.TASK_STARTED,
            objectAttributes("taskId", task.getId(), "title", task.getTitle(),
                "childRunId", child.getId(), "childAgentId", child.getAgent().getId()));
    }

    /** 发布子 Run 已经转换为任务最终状态的事件。 */
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

    /** 将生命周期事件追加到持久化事件流。 */
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
            values.putAll(attributes);
        }
        eventStore.append(AgentRunEvent.create(run.getId(), type, values));
    }

    private Map<String, String> iterationAttributes(AgentRun run) {
        int maxIterations = run.getExecutionPolicy().getMaxIterations();
        return attributes("iteration", String.valueOf(run.getIterationCount()),
            "maxIterations", String.valueOf(maxIterations),
            "remainingIterations", String.valueOf(
                Math.max(0, maxIterations - run.getIterationCount())));
    }

    /** 过滤空值并创建事件属性。 */
    private Map<String, String> attributes(String... values) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i + 1] != null) {
                attributes.put(values[i], values[i + 1]);
            }
        }
        return attributes;
    }

    /** 创建允许任意数据类型的实时事件属性。 */
    private Map<String, Object> objectAttributes(Object... values) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i] != null && values[i + 1] != null) {
                attributes.put(String.valueOf(values[i]), values[i + 1]);
            }
        }
        return attributes;
    }

    private Map<String, Object> mergeObjectAttributes(Map<String, Object> base,
                                                       Map<String, ?> additions) {
        if (additions != null) base.putAll(additions);
        return base;
    }

    private void emitRuntime(AgentRun run, AgentRuntimeEventType type,
                             Map<String, ?> data) {
        if (run != null) runtimeEventStream.publish(run, type, data);
    }

    private String errorMessage(Throwable error) {
        return error == null ? null : error.getClass().getName() + ": " + error.getMessage();
    }

    /** 将 Store 中的单调取消信号同步到当前内存 Run。 */
    private void refreshCancellation(AgentRun run) {
        if (!run.isCancellationRequested()
            && runStore.isCancellationRequested(run.getId())) {
            run.requestCancellation();
        }
    }

    private void forEachListener(ListenerCallback callback) {
        for (AgentListener listener : listeners) {
            try {
                callback.call(listener);
            } catch (RuntimeException error) {
                log.warn("Agent listener failed", error);
            }
        }
    }

    /** 封装一次监听器回调，使统一分发逻辑能够隔离单个监听器抛出的异常。 */
    private interface ListenerCallback { void call(AgentListener listener); }

    private static final class AgentRunCancelledException extends RuntimeException { }

    /** 表示恢复执行时，当前 Agent 已无法提供快照所记录的工具。 */
    private static final class AgentToolNotFoundException extends RuntimeException {
        private AgentToolNotFoundException(String name) { super("tool not found: " + name); }
    }

}

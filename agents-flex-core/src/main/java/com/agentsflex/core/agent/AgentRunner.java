/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.event.AgentRunEvent;
import com.agentsflex.core.agent.event.AgentRunEventStore;
import com.agentsflex.core.agent.event.AgentRunEventEnricher;
import com.agentsflex.core.agent.event.AgentRunEventType;
import com.agentsflex.core.agent.event.InMemoryAgentRunEventStore;
import com.agentsflex.core.agent.event.AgentRuntimeEventStream;
import com.agentsflex.core.agent.event.AgentRuntimeEventListener;
import com.agentsflex.core.agent.event.AgentRuntimeEventType;
import com.agentsflex.core.agent.command.AgentRunCommand;
import com.agentsflex.core.agent.command.AgentRunCommandStore;
import com.agentsflex.core.agent.command.AgentWakeupListener;
import com.agentsflex.core.agent.command.InMemoryAgentRunCommandStore;
import com.agentsflex.core.agent.context.AgentArtifactReference;
import com.agentsflex.core.agent.context.AgentArtifactStore;
import com.agentsflex.core.agent.context.AgentContextUpdate;
import com.agentsflex.core.agent.context.InMemoryAgentArtifactStore;
import com.agentsflex.core.agent.middleware.AgentMiddleware;
import com.agentsflex.core.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.core.agent.middleware.AgentModelCallChain;
import com.agentsflex.core.agent.middleware.AgentStepChain;
import com.agentsflex.core.agent.middleware.AgentToolCallChain;
import com.agentsflex.core.agent.middleware.AgentToolCallContext;
import com.agentsflex.core.agent.registry.AgentRegistry;
import com.agentsflex.core.agent.registry.InMemoryAgentRegistry;
import com.agentsflex.core.agent.mode.AgentExecutionContext;
import com.agentsflex.core.agent.store.AgentRunStore;
import com.agentsflex.core.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.agent.store.ParentChildRunSnapshots;
import com.agentsflex.core.agent.tool.AgentToolReference;
import com.agentsflex.core.agent.tool.AgentToolProgressEmitter;
import com.agentsflex.core.agent.tool.AgentToolInvocation;
import com.agentsflex.core.agent.tool.AgentToolRegistry;
import com.agentsflex.core.agent.tool.InMemoryAgentToolRegistry;
import com.agentsflex.core.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.agent.tool.ToolErrorStrategy;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.ChatContext;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按模型原生 ToolCall 协议推进 AgentRun 的执行器。
 *
 * <p>模型调用和工具调用都在稳定状态边界保存 Checkpoint。模型产生 ToolCall 后，Runner 会先
 * 保存待执行调用，再逐个完成审批、执行和结果写入，从而支持进程退出后的继续执行。</p>
 */
public final class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final AgentRunStore runStore;
    private final AgentRegistry agentRegistry;
    private final AgentToolRegistry toolRegistry;
    private final AgentRunEventStore eventStore;
    private final AgentRunCommandStore commandStore;
    private final AgentArtifactStore artifactStore;
    private final AgentRuntimeEventStream runtimeEventStream = new AgentRuntimeEventStream();
    private final AgentModelInvoker modelInvoker = new AgentModelInvoker(runtimeEventStream);
    private final List<AgentListener> listeners = new CopyOnWriteArrayList<>();
    private final List<AgentRunEventEnricher> eventEnrichers = new CopyOnWriteArrayList<>();
    private final List<AgentWakeupListener> wakeupListeners = new CopyOnWriteArrayList<>();
    /** 标识当前线程正在代表哪个 Worker 推进已领取的 Run。 */
    private final ThreadLocal<String> activeWorkerId = new ThreadLocal<>();

    public AgentRunner() {
        this(new InMemoryAgentRunStore(), new InMemoryAgentRegistry(),
            new InMemoryAgentToolRegistry(), new InMemoryAgentRunEventStore(),
            new InMemoryAgentRunCommandStore(), new InMemoryAgentArtifactStore());
    }

    /** 创建可按需替换 Store 和 Registry 的 Runner 构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    public AgentRunner(AgentRunStore runStore, AgentRegistry agentRegistry) {
        this(runStore, agentRegistry, new InMemoryAgentToolRegistry(),
            new InMemoryAgentRunEventStore(), new InMemoryAgentRunCommandStore(),
            new InMemoryAgentArtifactStore());
    }

    public AgentRunner(AgentRunStore runStore, AgentRegistry agentRegistry,
                       AgentToolRegistry toolRegistry) {
        this(runStore, agentRegistry, toolRegistry, new InMemoryAgentRunEventStore(),
            new InMemoryAgentRunCommandStore(), new InMemoryAgentArtifactStore());
    }

    public AgentRunner(AgentRunStore runStore, AgentRegistry agentRegistry,
                       AgentToolRegistry toolRegistry, AgentRunEventStore eventStore) {
        this(runStore, agentRegistry, toolRegistry, eventStore,
            new InMemoryAgentRunCommandStore(), new InMemoryAgentArtifactStore());
    }

    public AgentRunner(AgentRunStore runStore, AgentRegistry agentRegistry,
                       AgentToolRegistry toolRegistry, AgentRunEventStore eventStore,
                       AgentRunCommandStore commandStore, AgentArtifactStore artifactStore) {
        if (runStore == null || agentRegistry == null || toolRegistry == null || eventStore == null
            || commandStore == null || artifactStore == null) {
            throw new IllegalArgumentException(
                "AgentRunner dependencies must not be null");
        }
        this.runStore = runStore;
        this.agentRegistry = agentRegistry;
        this.toolRegistry = toolRegistry;
        this.eventStore = eventStore;
        this.commandStore = commandStore;
        this.artifactStore = artifactStore;
    }

    /**
     * AgentRunner 依赖构建器。
     *
     * <p>未显式配置的组件使用进程内实现，适合测试和本地开发。多实例部署应至少替换 RunStore、
     * CommandStore、Registry，并按需求替换 EventStore 和 ArtifactStore。</p>
     */
    public static final class Builder {
        private AgentRunStore runStore = new InMemoryAgentRunStore();
        private AgentRegistry agentRegistry = new InMemoryAgentRegistry();
        private AgentToolRegistry toolRegistry = new InMemoryAgentToolRegistry();
        private AgentRunEventStore eventStore = new InMemoryAgentRunEventStore();
        private AgentRunCommandStore commandStore = new InMemoryAgentRunCommandStore();
        private AgentArtifactStore artifactStore = new InMemoryAgentArtifactStore();

        public Builder runStore(AgentRunStore value) { runStore = value; return this; }
        public Builder agentRegistry(AgentRegistry value) { agentRegistry = value; return this; }
        public Builder toolRegistry(AgentToolRegistry value) { toolRegistry = value; return this; }
        public Builder eventStore(AgentRunEventStore value) { eventStore = value; return this; }
        public Builder commandStore(AgentRunCommandStore value) { commandStore = value; return this; }
        public Builder artifactStore(AgentArtifactStore value) { artifactStore = value; return this; }

        /** 校验全部依赖并创建 Runner。 */
        public AgentRunner build() {
            return new AgentRunner(runStore, agentRegistry, toolRegistry, eventStore,
                commandStore, artifactStore);
        }
    }

    public AgentRunStore getRunStore() { return runStore; }
    public AgentRegistry getAgentRegistry() { return agentRegistry; }
    public AgentToolRegistry getToolRegistry() { return toolRegistry; }
    public AgentRunEventStore getEventStore() { return eventStore; }
    public AgentRunCommandStore getCommandStore() { return commandStore; }
    public AgentArtifactStore getArtifactStore() { return artifactStore; }
    public AgentRuntimeEventStream getRuntimeEventStream() { return runtimeEventStream; }

    public AgentRunner addRuntimeEventListener(AgentRuntimeEventListener listener) {
        runtimeEventStream.addListener(listener);
        return this;
    }

    public AgentRunner addWakeupListener(AgentWakeupListener listener) {
        if (listener != null) wakeupListeners.add(listener);
        return this;
    }

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

    /** 创建并保存一个尚未执行的 Run。 */
    public AgentRun start(Agent agent, String userInput) {
        return start(agent, userInput, AgentRunOptions.defaults());
    }

    /** 创建并保存一个带有运行时覆盖参数的任务。 */
    public AgentRun start(Agent agent, String userInput, AgentRunOptions options) {
        registerAgent(agent);
        AgentRun run = AgentRun.start(agent, userInput, options);
        checkpoint(run);
        return run;
    }

    public AgentRun run(AgentRun run) { return runUntilBlocked(run); }

    /** 持续推进，直到任务结束或等待外部事件。 */
    public AgentRun runUntilBlocked(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        ensureRegisteredAndCheckpointed(run);
        refreshCancellation(run);
        while (!run.getStatus().isTerminal()
            && (!run.getStatus().isBlocked() || run.isCancellationRequested())) {
            step(run);
        }
        return run;
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
        if (requested) {
            notifyCancellationRequested(run);
        }
        return run;
    }

    /** 从 Store 恢复最新 Checkpoint。 */
    public AgentRun restore(String runId) {
        return restore(runId, AgentInvocationContext.empty());
    }

    /** 从 Store 恢复 Run，并附加当前进程使用的调用上下文。 */
    public AgentRun restore(String runId, AgentInvocationContext invocationContext) {
        AgentRunSnapshot snapshot = runStore.load(runId);
        if (snapshot == null) {
            throw new IllegalStateException("AgentRun checkpoint not found: " + runId);
        }
        Agent agent = agentRegistry.resolve(snapshot.getAgentId(), snapshot.getAgentVersion());
        if (agent == null) {
            throw new IllegalStateException("Agent is not registered: " + snapshot.getAgentId()
                + ", version=" + snapshot.getAgentVersion());
        }
        registerAgentTools(agent);
        return AgentRun.fromSnapshot(agent, snapshot).attachInvocationContext(invocationContext);
    }

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

    /** 应用外部命令并继续推进。 */
    public AgentRun resume(AgentRun run, AgentResumeCommand command) {
        return runUntilBlocked(submitResume(run, command));
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

    public AgentRun resume(String runId, AgentResumeCommand command) {
        return resume(restore(runId), command);
    }

    public AgentRun submitResume(String runId, AgentResumeCommand command) {
        return submitResume(restore(runId), command);
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
            return commandStore.submit(AgentRunCommand.pending(commandId, runId, command));
        }
        AgentRun run = restore(runId);
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

    /** 领取并应用一批持久化恢复命令，使对应 Run 重新进入可调度状态。 */
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

    /** 保存稳定状态并更新本地乐观锁版本。 */
    public AgentRunSnapshot checkpoint(AgentRun run) {
        assertLeaseOwnership(run);
        AgentRunSnapshot saved = runStore.save(run.toSnapshot(), run.getVersion());
        run.updateVersion(saved.getVersion());
        if (saved.isCancellationRequested()) {
            run.requestCancellation();
        }
        notifyCheckpoint(run, saved);
        return saved;
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
            if (run != null && run.getStatus().isTerminal()) {
                runtimeEventStream.release(run.getId());
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
        ensureRegisteredAndCheckpointed(run);
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

    /** 创建子 Run，并让父 Run 等待子任务完成。 */
    public AgentRun startChild(AgentRun parent, String childAgentId, String input) {
        assertLeaseOwnership(parent);
        Agent childAgent = agentRegistry.resolveLatest(childAgentId);
        if (childAgent == null) {
            throw new IllegalStateException("Child agent is not registered: " + childAgentId);
        }
        registerAgent(childAgent);
        AgentRun child = AgentRun.startChild(childAgent, input, parent);
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
        return child;
    }

    /**
     * 将终止子 Run 的结果交回正在等待它的父 Run。
     *
     * <p>该方法具备幂等检查：父 Run 已不再等待当前 childRunId 时不会重复写入消息。</p>
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
        result.put("output", child.getFinalOutput());
        result.put("error", child.getError() == null ? null : child.getError().getMessage());
        parent.getPrompt().addUserMessage("Child Agent result: " + JSON.toJSONString(result));
        return submitResume(parent, AgentResumeCommand.childCompleted(child.getId()));
    }

    /** 在指定 Worker 的租约上下文中推进已经领取的 Run。 */
    AgentRun runLeased(AgentRun run, String workerId) {
        if (!workerId.equals(run.getLeaseOwner())
            || run.getLeaseUntil() <= System.currentTimeMillis()) {
            throw new IllegalStateException("AgentRun lease is not active for worker: " + workerId);
        }
        activeWorkerId.set(workerId);
        try {
            if (run.getStatus() == AgentRunStatus.RETRY_SCHEDULED) {
                return resume(run, AgentResumeCommand.retry());
            }
            return runUntilBlocked(run);
        } finally {
            activeWorkerId.remove();
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
        copyResolvedToolsBackToRun(run, response);
        try {
            capturePendingToolReferences(run, response);
        } catch (RuntimeException error) {
            return handleFailure(run, response, error, AgentRunPhase.TOOLS);
        }
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

    private Tool resolveTool(AgentRun run, ToolCall call) {
        AgentToolReference reference = run.getPendingToolReference(callKey(call));
        if (reference == null) {
            return null;
        }
        return toolRegistry.resolve(reference);
    }

    /**
     * 在模型确定 ToolCall 后冻结实际工具引用，使后续 Checkpoint 能够在其他进程中解析同一工具绑定。
     */
    private void capturePendingToolReferences(AgentRun run, AiMessageResponse response) {
        Map<String, Tool> executionTools = getExecutionTools(run, response);
        for (ToolCall call : run.getPendingToolCalls()) {
            if (call == null) {
                continue;
            }
            Tool tool = executionTools.get(call.getName());
            if (tool == null) {
                throw new AgentToolNotFoundException(call.getName());
            }
            AgentToolReference reference = registerTool(run.getAgent(), tool);
            run.putPendingToolReference(callKey(call), reference);
        }
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

    private Map<String, Tool> getExecutionTools(AgentRun run, AiMessageResponse response) {
        ChatContext context = response == null ? null : response.getContext();
        Prompt executionPrompt = context == null ? null : context.getPrompt();
        return executionPrompt == null
            ? run.getPrompt().getToolsMap() : executionPrompt.getToolsMap();
    }

    private ToolMessage executeTool(AgentRun run, Tool tool, ToolCall call) {
        List<ToolInterceptor> interceptors = run.getAgent().getToolInterceptors();
        AgentToolReference reference = run.getPendingToolReference(callKey(call));
        AgentToolInvocation invocation = new AgentToolInvocation(
            run.getId(), run.getRootRunId(), run.getParentRunId(),
            run.getAgent().getId(), run.getAgent().getVersion(), callKey(call), reference);
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

    private void copyResolvedToolsBackToRun(AgentRun run, AiMessageResponse response) {
        ChatContext context = response == null ? null : response.getContext();
        Prompt executionPrompt = context == null ? null : context.getPrompt();
        if (executionPrompt != null) {
            if (executionPrompt != run.getPrompt()) {
                run.getPrompt().setTools(executionPrompt.getTools());
            }
            for (Tool tool : executionPrompt.getTools()) {
                registerTool(run.getAgent(), tool);
            }
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

    private void ensureRegisteredAndCheckpointed(AgentRun run) {
        registerAgent(run.getAgent());
        if (run.getVersion() < 0) {
            checkpoint(run);
        }
    }

    /** 拒绝非租约持有者推进仍处于有效租约中的 Run。 */
    private void assertLeaseOwnership(AgentRun run) {
        if (!StringUtil.hasText(run.getLeaseOwner())
            || run.getLeaseUntil() <= System.currentTimeMillis()) {
            return;
        }
        if (!run.getLeaseOwner().equals(activeWorkerId.get())) {
            throw new IllegalStateException("AgentRun is leased by worker: " + run.getLeaseOwner());
        }
    }

    private void registerAgent(Agent agent) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
        agentRegistry.register(agent);
        registerAgentTools(agent);
    }

    private void registerAgentTools(Agent agent) {
        for (Tool tool : agent.getTools()) {
            registerTool(agent, tool);
        }
    }

    /** 创建持久化引用并注册对应的运行时工具。 */
    private AgentToolReference registerTool(Agent agent, Tool tool) {
        return toolRegistry.register(agent.getId(), agent.getVersion(), tool);
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

    private interface ListenerCallback { void call(AgentListener listener); }

    private static final class AgentRunCancelledException extends RuntimeException { }

    private static final class AgentToolNotFoundException extends RuntimeException {
        private AgentToolNotFoundException(String name) { super("tool not found: " + name); }
    }

}

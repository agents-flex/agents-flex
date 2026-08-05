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
import com.agentsflex.agent.middleware.AgentToolCallContext;
import com.agentsflex.agent.loader.AgentLoader;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentRunStore;
import com.agentsflex.agent.store.AgentRunVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.store.ParentChildRunSnapshots;
import com.agentsflex.agent.tool.AgentToolProgressEmitter;
import com.agentsflex.agent.tool.AgentToolInvocation;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.agent.tool.ToolErrorStrategy;
import com.agentsflex.agent.task.AgentTaskProgress;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *     <li>内置 ToolCall 状态机根据当前 Phase 推进模型调用或工具执行。</li>
 * </ol>
 *
 * <p>内置状态机使用模型原生 ToolCall。模型产生 ToolCall 后，Runner 先把调用及参数保存为
 * Snapshot，再逐个完成审批、工具执行和 ToolMessage 写入。审批恢复时因此可以继续执行已经确认的
 * 原始 ToolCall，而不需要重新请求模型生成参数。</p>
 *
 * <p>Runner 同时负责预算检查、自动重试、暂停恢复、任务规划、父子 Run 协调和生命周期事件。
 * 所有需要跨进程恢复的状态最终通过 {@link AgentRunStore} 持久化；Runner 自身不长期保存任务状态，
 * 因而通常作为应用级对象复用。</p>
 *
 * <p>直接调用 {@code run(...)} 会在当前线程推进子 Run；分布式长任务应先调用 {@code start(...)}
 * 保存 READY Snapshot，再由 AgentWorker 通过租约领取。不要让两个线程直接推进同一个
 * AgentRun 对象。</p>
 */
public final class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /**
     * 保存 Snapshot、取消标记和 Worker 租约的运行存储。
     */
    private final AgentRunStore runStore;
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
     * 标识当前线程正在代表哪个 Worker 推进已领取的 Run。
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
     * 创建自定义 RunStore 和 AgentLoader 的 Runner。
     */
    public AgentRunner(AgentRunStore runStore, AgentLoader agentLoader) {
        if (runStore == null || agentLoader == null) {
            throw new IllegalArgumentException(
                "AgentRunner dependencies must not be null");
        }
        this.runStore = runStore;
        this.agentLoader = agentLoader;
        this.eventPublisher = new AgentEventPublisher();
        this.planning = new AgentRunnerPlanning(this, agentLoader, eventPublisher);
        this.modelInvoker = new AgentModelInvoker(eventPublisher);
    }

    /**
     * AgentRunner 依赖构建器。
     *
     * <p>未显式配置的组件使用进程内实现，适合测试和本地开发。多实例部署应替换 RunStore 和
     * AgentLoader。AgentLoader 必须返回包含完整工具集合的可执行 Agent。</p>
     */
    public static final class Builder {
        private AgentRunStore runStore = new InMemoryAgentRunStore();
        private AgentLoader agentLoader = new InMemoryAgentLoader();

        /**
         * 设置 Snapshot 与租约存储。
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
         * 校验全部依赖并创建 Runner。
         */
        public AgentRunner build() {
            return new AgentRunner(runStore, agentLoader);
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
     * 添加统一事件监听器。
     *
     * <p>监听器在发布线程同步执行，只用于观察；单个监听器异常会被隔离，不会让 Run 失败。</p>
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
     * <p>这是携带历史消息创建 Run 的统一入口。方法只建立可恢复的 READY 状态，不调用模型；
     * 初始 Snapshot 成功后，Run 才会返回给调用方或后台调度器。</p>
     */
    public AgentRun start(Agent agent, List<? extends Message> conversationHistory,
                          UserMessage userMessage, AgentRunOptions options) {
        // 先准备 Agent 和规划工具，再创建 Run，确保初始 Snapshot 已包含完整可执行状态。
        prepareAgent(agent);
        AgentRun run = AgentRun.start(agent, conversationHistory, userMessage, options);
        prepareRun(run);
        // 初始 Snapshot 使任务在第一次模型调用之前就可以被 Worker 发现和恢复。
        saveSnapshot(run);
        return run;
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
        ensurePreparedAndSnapshotSaved(current);
        refreshCancellation(current);
        while (true) {
            // 普通状态持续单步推进；若阻塞期间收到取消信号，也要再执行一步完成 CANCELLED 落盘。
            while (!current.getStatus().isTerminal()
                && (!current.getStatus().isBlocked() || current.isCancellationRequested())) {
                step(current);
            }

            // 没有活动子任务时，当前 Run 已经到达本次调用的返回边界。
            AgentRun child = planning.currentChild(current);
            if (child == null || current.isCancellationRequested()) return current;

            // Worker 只能推进自己通过 Store 领取的 Run，子 Run 必须另行领取并获得独立租约。
            if (activeWorkerId.get() != null) return current;

            // 同步模式递归执行子 Run；子 Run 若再次阻塞，则保持父 Run 的 WAITING_FOR_CHILD 状态返回。
            child = runUntilBlocked(child);
            if (!child.getStatus().isTerminal()) return current;

            // 子 Run 终止后把结果写回父 Run，再从父 Run 原来的恢复 Phase 继续外层循环。
            current = resumeParentFromChild(child);
            if (current == null) return run;
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
        AgentRun child = planning.currentChild(run);
        if (child != null && !child.getStatus().isTerminal()) {
            runStore.requestCancellation(child.getId());
        }
        if (requested) {
            eventPublisher.notifyCancellationRequested(run);
        }
        return run;
    }

    /**
     * 从 Store 恢复最新 Snapshot。
     *
     * <p>方法按照快照中的 agentId 和 agentVersion 加载匹配定义，不使用当前生效版本。</p>
     */
    public AgentRun restore(String runId) {
        // Snapshot 是恢复状态的事实来源；运行时对象不能从旧 JVM 内存中获取。
        AgentRunSnapshot snapshot = runStore.load(runId);
        if (snapshot == null) {
            throw new IllegalStateException("AgentRun snapshot not found: " + runId);
        }
        // 必须加载创建 Run 时记录的版本，避免用最新配置解释待执行 ToolCall。
        Agent agent = agentLoader.load(snapshot.getAgentId(), snapshot.getAgentVersion());
        if (agent == null) {
            throw new IllegalStateException("Agent cannot be loaded: " + snapshot.getAgentId()
                + ", version=" + snapshot.getAgentVersion());
        }
        return AgentRun.fromSnapshot(agent, snapshot);
    }

    /**
     * 从最新 Snapshot 恢复指定 Run 并推进到下一个稳定边界。
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
        saveSnapshot(run);
        eventPublisher.notifyRunSuspended(run, suspension);
        return run;
    }

    /**
     * 应用外部命令并在当前线程继续推进。
     *
     * <p>根 Run 正在等待规划子 Run 时，命令会自动路由到实际阻塞的子 Run；子 Run 终止后再恢复并
     * 推进父 Run。</p>
     */
    public AgentRun resume(AgentRun run, AgentResumeCommand command) {
        AgentRun child = planning.currentChild(run);
        if (child == null) return runUntilBlocked(submitResume(run, command));
        AgentRun resumedChild = resume(child, command);
        if (!resumedChild.getStatus().isTerminal()) return run;
        AgentRun parent = resumeParentFromChild(resumedChild);
        return parent == null ? run : runUntilBlocked(parent);
    }

    /**
     * 提交恢复命令并保存为可运行状态，但不在当前线程继续执行。
     *
     * <p>事件消费者可以使用该方法唤醒任务，再由 AgentWorker 通过租约领取执行。</p>
     */
    public AgentRun submitResume(AgentRun run, AgentResumeCommand command) {
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
        // 恢复到暂停前保存的模型或工具阶段，不从任务开头重新执行。
        run.resumeAt(suspension.getResumePhase());
        saveSnapshot(run);
        eventPublisher.notifyRunResumed(run, command);
        return run;
    }

    /**
     * 恢复指定 ID 的阻塞 Run，并同步推进到下一个稳定边界。
     */
    public AgentRun resume(String runId, AgentResumeCommand command) {
        return resume(restore(runId), command);
    }

    /**
     * 恢复指定 ID 的 Run 但不继续执行；规划场景会自动路由到当前活动子 Run。
     */
    public AgentRun submitResume(String runId, AgentResumeCommand command) {
        AgentRun run = restore(runId);
        AgentRun child = planning.currentChild(run);
        return submitResume(child == null ? run : child, command);
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
    public AgentRunSnapshot saveSnapshot(AgentRun run) {
        synchronized (run) {
            // 同步块保证同一 JVM 中 Snapshot 构造、Store CAS 和本地版本更新不可交错。
            assertLeaseOwnership(run);
            AgentRunSnapshot saved = runStore.save(run.toSnapshot(), run.getVersion());
            run.updateVersion(saved.getState().getVersion());
            if (saved.getState().isCancellationRequested()) {
                run.requestCancellation();
            }
            eventPublisher.notifySnapshotSaved(run, saved);
            return saved;
        }
    }

    /**
     * 推进当前模型或工具步骤。
     *
     * <p>一次调用最多调用模型一次，但可以顺序处理该模型回合产生的全部 ToolCall。方法返回
     * {@link AgentStepResult} 描述本步结果；是否继续下一步由 {@link #runUntilBlocked(AgentRun)} 决定。</p>
     *
     * <p>步骤开始和结束事件在最外层发布，所有执行路径共享相同的生命周期语义。</p>
     */
    public AgentStepResult step(AgentRun run) {
        eventPublisher.publish(run, AgentEventType.STEP_STARTED,
            objectAttributes("phase", run == null ? null : run.getPhase()));
        try {
            AgentStepResult result = stepCore(run);
            eventPublisher.publish(run, AgentEventType.STEP_COMPLETED,
                objectAttributes("status", run == null ? null : run.getStatus(),
                    "phase", run == null ? null : run.getPhase(),
                    "toolMessageCount", result == null ? 0 : result.getToolMessages().size()));
            return result;
        } finally {
            if (run != null && run.getStatus().isTerminal()) {
                eventPublisher.clearSequence(run.getId());
            }
        }
    }

    /**
     * 以责任链方式执行 step Middleware，链尾进入内置 ToolCall 状态机。
     *
     * <p>每个 Middleware 可以在调用 next 前后观察或增强步骤，但应保持链只推进一次。</p>
     */
    private AgentStepResult proceedStep(AgentRun run, AgentMiddlewareContext context, int index) {
        List<AgentMiddleware> middlewares = run == null
            ? Collections.<AgentMiddleware>emptyList() : run.getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
            return executeToolCallingStep(run);
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
        ensurePreparedAndSnapshotSaved(run);

        // Lease 和持久化取消标记必须在任何模型或工具副作用之前检查。
        assertLeaseOwnership(run);
        refreshCancellation(run);
        if (run.isCancellationRequested()) {
            return cancelRun(run);
        }
        if (run.getStatus().isBlocked()) {
            // 阻塞 Run 只能通过类型化 ResumeCommand 改回可运行状态，step 本身不能越过等待边界。
            return AgentStepResult.of(null, null, null);
        }
        if (run.markStarted()) {
            eventPublisher.notifyRunStart(run);
        }
        // 时间和累计 Token 预算在每一步入口检查；工具次数还会在具体工具执行前再次检查。
        String budgetReason = budgetExceededReason(run, false);
        if (budgetReason != null) {
            return budgetExceeded(run, budgetReason);
        }

        if (run.getStepCount() >= run.getExecutionPolicy().getMaxSteps()) {
            run.markMaxStepsReached();
            saveSnapshot(run);
            eventPublisher.notifyMaxStepsReached(run);
            return AgentStepResult.of(null, null, null);
        }
        run.incrementStep();

        // 已存在的任务计划优先于下一次模型调用推进，避免计划任务与普通对话循环互相竞争。
        AgentStepResult planningResult = planning.advance(run);
        if (planningResult != null) return planningResult;

        // 规划没有产生独立动作时，再进入 Middleware 和内置 ToolCall 状态机。
        AgentStepResult result = proceedStep(run,
            new AgentMiddlewareContext(this, run, run.getPrompt()), 0);
        if (result == null) {
            return handleFailure(run, null,
                new IllegalStateException("Agent step returned null result"),
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
     * 使用模型原生 ToolCall 协议推进一个稳定执行步骤。
     *
     * <p>Phase 是恢复游标而不是业务状态：MODEL 表示下一步应请求模型，TOOLS 表示模型已经生成了
     * 尚未处理完的 ToolCall，FINISHED 表示运行已经结束。</p>
     *
     * <p>MODEL 阶段最多调用模型一次；TOOLS 阶段按顺序处理当前模型回合遗留的全部工具调用，并在
     * 每个结果写入后保存 Snapshot。</p>
     */
    private AgentStepResult executeToolCallingStep(AgentRun run) {
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
        planning.bindChild(parent, child, childAgentId);
        AgentSuspension suspension = AgentSuspension.child(child.getId());
        AgentRunSnapshot parentSnapshot = parent.toSnapshot();
        AgentRunSnapshot parentWaiting = parentSnapshot.withState(parentSnapshot.getState().toBuilder()
            .status(AgentRunStatus.WAITING_FOR_CHILD)
            .phase(suspension.getResumePhase())
            .suspension(suspension)
            .build());
        // 父等待状态与子 READY 状态必须原子提交，避免出现孤儿子任务或永久等待的父任务。
        ParentChildRunSnapshots saved = runStore.saveParentAndChild(
            parentWaiting, parent.getVersion(), child.toSnapshot());
        parent.suspend(AgentRunStatus.WAITING_FOR_CHILD, suspension);
        parent.updateVersion(saved.getParent().getState().getVersion());
        child.updateVersion(saved.getChild().getState().getVersion());
        eventPublisher.notifySnapshotSaved(parent, saved.getParent());
        eventPublisher.notifySnapshotSaved(child, saved.getChild());
        eventPublisher.notifyRunSuspended(parent, suspension);
        eventPublisher.notifyChildStarted(parent, child);
        planning.notifyTaskStarted(parent, child);
        return child;
    }

    /**
     * 将终止子 Run 的结果交回正在等待它的父 Run。
     *
     * <p>该方法具备幂等检查：父 Run 已不再等待当前 childRunId 时不会重复写入消息。任务结果按父
     * Agent 的 planningPolicy 限制写回长度，子 Run 中的完整最终输出不会被修改。</p>
     */
    public AgentRun resumeParentFromChild(AgentRun child) {
        return planning.resumeParentFromChild(child);
    }

    /**
     * 查询根 Run 中的计划以及当前子 Run 的真实阻塞状态。
     */
    public AgentTaskProgress getTaskProgress(String runId) {
        return planning.getTaskProgress(runId);
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
                AgentRun child = restore(snapshot.getState().getRunId());
                AgentRun parent = resumeParentFromChild(child);
                if (parent != null && !parent.getStatus().isBlocked()) recovered++;
            } catch (AgentRunVersionConflictException ignored) {
                // 另一个 Worker 已经完成相同修复，下一次查询会自然过滤该父 Run。
            } catch (IllegalStateException error) {
                log.debug("Completed child recovery skipped, childRunId={}",
                    snapshot.getState().getRunId(), error);
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
            saveSnapshot(run);
            eventPublisher.notifyMaxIterationsReached(run);
            return AgentStepResult.of(null, null, null);
        }

        // 迭代次数表示模型调用次数，在发起请求前增加，失败的模型请求同样消耗一次尝试。
        run.incrementIteration();
        eventPublisher.notifyModelStart(run);
        AiMessageResponse response;
        try {
            // 模型 Middleware 以责任链包裹最终调用，可用于 tracing、缓存或受控 Prompt 增强。
            AgentMiddlewareContext middlewareContext = new AgentMiddlewareContext(
                this, run, run.getPrompt());
            response = proceedModelCall(run, middlewareContext, 0);
            validateResponse(response);
            eventPublisher.notifyModelEnd(run, response);
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
            // 不含 ToolCall 的 AI 消息是内置状态机的最终回答。
            return complete(run, response, message);
        }

        // 先保存模型决策，再执行可能产生外部副作用的工具。
        run.setPendingToolCalls(message.getToolCalls());
        run.moveTo(AgentRunPhase.TOOLS);
        saveSnapshot(run);
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
     * 逐个处理待执行 ToolCall，并在每个结果写入后保存 Snapshot。
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
            if (planning.isPlanningTool(call)) {
                try {
                    // 规划工具只转换 Run 内部状态，不经过业务工具审批和外部执行器。
                    ToolMessage planned = planning.applyToolCall(run, call);
                    appendToolResult(run, call, planned);
                    results.add(planned);
                    planning.notifyPlanChanged(run, call);
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
                eventPublisher.notifyToolApprovalRequested(run, call, decision);
                return AgentStepResult.of(response, results, null);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.DENY) {
                // 拒绝不是运行时异常，而是结构化 ToolMessage；模型可据此向用户解释或选择替代方案。
                ToolMessage rejected = buildToolRejectedMessage(run, call, decision);
                appendToolResult(run, call, rejected);
                results.add(rejected);
                continue;
            }

            eventPublisher.notifyToolStart(run, call);
            ToolMessage completedResult;
            try {
                run.incrementToolCallCount();
                completedResult = executeTool(run, tool, call);
            } catch (RuntimeException error) {
                eventPublisher.notifyToolError(run, call, error);
                if (run.getExecutionPolicy().getToolErrorStrategy()
                    == ToolErrorStrategy.RETURN_ERROR_TO_MODEL) {
                    // 将错误交给模型时仍生成与原 ToolCall 匹配的 ToolMessage，保持协议完整。
                    completedResult = buildToolErrorMessage(call, error);
                } else {
                    return handleFailure(run, response, error, AgentRunPhase.TOOLS);
                }
            }
            // Snapshot 异常必须直接交给调用方，不能被误判为工具执行异常。
            appendToolResult(run, call, completedResult);
            results.add(completedResult);
            eventPublisher.notifyToolEnd(run, call);
            refreshCancellation(run);
        }

        // 当前模型回合的全部工具调用已处理，下一 step 应让模型读取 ToolMessage 并继续判断。
        run.moveTo(AgentRunPhase.MODEL);
        saveSnapshot(run);
        return AgentStepResult.of(response, results, null);
    }

    /**
     * 原子语义上提交一个工具结果：追加消息、移除 pending 调用并保存 Snapshot。
     *
     * <p>只有 Snapshot 成功后调用才算被运行时确认；具备外部副作用的 Tool 仍应使用
     * {@link AgentToolInvocation} ID 在业务侧实现幂等。</p>
     */
    private void appendToolResult(AgentRun run, ToolCall call, ToolMessage result) {
        run.getPrompt().addMessage(result);
        run.removeFirstPendingToolCall();
        saveSnapshot(run);
    }

    /**
     * 从恢复出的当前 Agent 定义中按名称解析工具；工具对象本身不保存在 Snapshot。
     */
    private Tool resolveTool(AgentRun run, ToolCall call) {
        return call == null ? null : run.getAgent().getTool(call.getName());
    }

    /**
     * 将模型或工具异常统一转换为取消、持久化重试或最终失败状态。
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
            saveSnapshot(run);
            eventPublisher.notifyRunSuspended(run, run.getSuspension());
            eventPublisher.notifyRetryScheduled(run, error);
            return AgentStepResult.of(response, null, error);
        }
        run.markFailed(error);
        saveSnapshot(run);
        eventPublisher.notifyRunFailed(run, error);
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
     * 保存最终消息、收束计划状态，并将 Run 转换为不可再次推进的 COMPLETED 状态。
     */
    AgentStepResult complete(AgentRun run, AiMessageResponse response, AiMessage message) {
        planning.finishPlan(run);
        run.markCompleted(message == null ? new AiMessage("") : message);
        saveSnapshot(run);
        eventPublisher.notifyRunComplete(run);
        return AgentStepResult.of(response, null, null);
    }

    /**
     * 在安全边界响应单调取消信号并保存最终 CANCELLED 状态。
     */
    private AgentStepResult cancelRun(AgentRun run) {
        run.markCancelled();
        saveSnapshot(run);
        eventPublisher.notifyRunCancelled(run);
        return AgentStepResult.of(null, null, null);
    }

    /**
     * 保存预算终止原因，避免调用方只能从通用失败信息推断成本限制。
     */
    private AgentStepResult budgetExceeded(AgentRun run, String reason) {
        run.markBudgetExceeded(reason);
        saveSnapshot(run);
        eventPublisher.notifyBudgetExceeded(run, reason);
        return AgentStepResult.of(null, null, null);
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
     * Agent Middleware、ToolInterceptor、Tool 函数；调用标识和进度发射器通过工具上下文 attributes
     * 传入，不写入工具参数 Schema。</p>
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
            attributes.put(AgentToolProgressEmitter.CONTEXT_ATTRIBUTE,
                (AgentToolProgressEmitter) (message, data) -> eventPublisher.notifyToolProgress(
                    run, context.getToolCall(), context.getTool().getName(), message, data));
            return new ToolExecutor(context.getTool(), context.getToolCall(), interceptors)
                .execute(attributes);
        }
        AgentMiddleware middleware = middlewares.get(index);
        AgentToolCallChain chain = next -> proceedToolCall(run, next, index + 1,
            invocation, interceptors);
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
     * 从消息历史倒序查找最近的 AI 消息，供 FINISHED Phase 完成运行。
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
    private void ensurePreparedAndSnapshotSaved(AgentRun run) {
        prepareRun(run);
        if (run.getVersion() < 0) {
            saveSnapshot(run);
        }
    }

    /**
     * 解析规划白名单中的完整 Agent，并为当前 Run 装配模型可见的规划工具。
     */
    private void prepareRun(AgentRun run) {
        if (run == null) throw new IllegalArgumentException("run must not be null");
        prepareAgent(run.getAgent());
        planning.prepareTools(run);
    }

    /**
     * 拒绝非租约持有者推进仍处于有效租约中的 Run。
     *
     * <p>Worker 路径同时校验 owner、唯一 leaseId 和存储端到期时间；同步 API 路径只能推进当前没有
     * 有效 Lease 的 Run。该检查必须位于每个副作用和 Snapshot 之前，防止过期 Worker 覆盖新状态。</p>
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
     * Snapshot，避免部分应用一个无效命令。</p>
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
     * 将 Store 中的单调取消信号同步到当前内存 Run。
     */
    private void refreshCancellation(AgentRun run) {
        if (!run.isCancellationRequested()
            && runStore.isCancellationRequested(run.getId())) {
            run.requestCancellation();
        }
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

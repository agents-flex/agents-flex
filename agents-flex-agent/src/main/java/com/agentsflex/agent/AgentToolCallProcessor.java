/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.exception.AgentApprovalRequiredException;
import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentToolCallChain;
import com.agentsflex.agent.tool.*;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatContextHolder;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutionTarget;
import com.agentsflex.core.model.chat.tool.ToolExecutor;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;

import java.util.*;
import java.util.concurrent.*;

/**
 * 推进当前模型回合中尚未处理的 {@link ToolCall}。
 *
 * <p>该组件负责工具阶段内部的完整流程：解析工具、检查中央审批策略、识别内置用户输入工具、
 * 区分本地与外部执行目标、构造工具 Middleware 链、执行超时控制、处理 Tool 主动发起的审批或
 * 表单请求，以及按模型声明顺序写入 {@link ToolMessage}。执行策略允许时，同一模型回合中的本地
 * 工具可以并行运行，但结果仍按原始 ToolCall 顺序提交。</p>
 *
 * <p>该组件不拥有 Turn 的公共生命周期，也不直接操作 Store。取消、预算超限、失败重试、挂起、
 * Snapshot 和终态转换统一委托给 {@link AgentRunner}，从而保证模型阶段和工具阶段使用相同的
 * 持久化边界与事件顺序。该类仅作为 agent 包内实现细节，不增加对外 API。</p>
 */
final class AgentToolCallProcessor {

    private static final String APPROVAL_TRIGGER_METADATA = "agentsflex.approvalTrigger";
    private static final String TOOL_APPROVAL_TRIGGER = "TOOL";

    private final AgentRunner runner;
    private final AgentEventPublisher eventPublisher;
    private final AgentRunnerOptions runnerOptions;

    /**
     * 创建工具调用处理器。
     *
     * @param runner         提供生命周期转换、预算判断和 Snapshot 保存能力的 Runner
     * @param eventPublisher 发布工具开始、完成、失败、审批和输入请求事件的组件
     * @param runnerOptions  提供实际执行本地工具的 Executor 等运行基础设施
     */
    AgentToolCallProcessor(AgentRunner runner, AgentEventPublisher eventPublisher,
                           AgentRunnerOptions runnerOptions) {
        if (runner == null || eventPublisher == null || runnerOptions == null) {
            throw new IllegalArgumentException("tool call processor dependencies must not be null");
        }
        this.runner = runner;
        this.eventPublisher = eventPublisher;
        this.runnerOptions = runnerOptions;
    }

    /**
     * 推进当前模型回合中全部尚未完成的 ToolCall，直到工具全部完成或 Turn 到达稳定阻塞边界。
     *
     * <p>方法默认按模型声明顺序执行。配置为并行模式且当前批次均为已获中央策略允许的本地业务工具时，
     * 会先尝试并行路径；不满足并行前置条件时自动回退到顺序路径。每个成功或拒绝结果都会先追加到
     * Prompt、移除对应 pending ToolCall 并保存 Snapshot，之后才继续下一个调用。</p>
     *
     * @param turn     当前处于 PROCESS_TOOLS 执行点的 Turn
     * @param response 产生本批 ToolCall 的模型响应；从 Snapshot 恢复执行时可以为空
     * @return 本步骤已经产生的工具消息、原模型响应或失败信息
     */
    AgentStepResult executePendingTools(AgentTurn turn, AiMessageResponse response) {
        // 工具及其拦截器可能读取 ChatContext；恢复路径没有原响应时需要从 Turn 补建上下文。
        ChatContext chatContext = chatContextForToolExecution(turn, response);
        // 并行处理器用 null 表示当前批次不适合并行，调用方继续使用语义更完整的顺序路径。
        if (turn.getExecutionPolicy().getToolExecutionMode() == AgentToolExecutionMode.PARALLEL
            && turn.getPendingToolCalls().size() > 1) {
            AgentStepResult parallel = executePendingToolsInParallel(turn, response, chatContext);
            if (parallel != null) return parallel;
        }
        List<ToolMessage> results = new ArrayList<>();
        while (!turn.getPendingToolCalls().isEmpty()) {
            // 每个工具副作用前重新检查取消和通用预算，避免一个长批次越过控制面更新。
            if (turn.isCancellationRequested()) {
                return runner.cancelTurn(turn);
            }
            String budgetReason = runner.budgetExceededReason(turn, false);
            if (budgetReason != null) {
                return runner.budgetExceeded(turn, budgetReason);
            }

            // 顺序路径始终处理队首；只有结果成功持久化后才会移除该 pending 调用。
            ToolCall call = turn.getPendingToolCalls().get(0);
            Tool tool = resolveTool(turn, call);
            if (tool == null) {
                // Snapshot 只保存工具名称；恢复后缺少同版本工具属于不可重试的配置错误。
                return runner.handleFailure(turn, response,
                    new AgentRunner.AgentToolNotFoundException(call.getName()),
                    AgentTurnExecutionPoint.PROCESS_TOOLS);
            }

            if (AgentUserInputTool.isUserInputTool(tool)) {
                // 内置输入工具不执行业务函数，而是把 Schema 固化到 Suspension 后等待用户提交。
                try {
                    AgentFormDefinition form = AgentUserInputTool.resolveForm(tool, call);
                    Object title = form.getSchema().get("title");
                    String message = title == null ? form.getFormKey() : String.valueOf(title);
                    AgentSuspension suspension = AgentSuspension.userInput(
                        callKey(call), message, form.getFormKey(), form.getSchema(),
                        AgentUserInputTool.NAME, null,
                        turn.getExecutionPolicy().getUserInputTimeoutMillis());
                    runner.suspend(turn, suspension);
                    return AgentStepResult.of(response, results, null);
                } catch (RuntimeException error) {
                    return runner.handleFailure(turn, response, error,
                        AgentTurnExecutionPoint.PROCESS_TOOLS);
                }
            }

            // 内置控制工具不计入业务工具预算，因此 maxToolCalls 只在确定执行真实工具后检查。
            budgetReason = runner.budgetExceededReason(turn, true);
            if (budgetReason != null) {
                return runner.budgetExceeded(turn, budgetReason);
            }

            // 中央策略是第一层授权边界；已恢复的审批记录优先于再次调用动态策略。
            ToolApprovalDecision decision;
            try {
                ToolApprovalRecord policyRecord = turn.getToolApprovalRecord(
                    callKey(call), ToolApprovalStage.POLICY);
                decision = policyRecord == null
                    ? turn.getAgent().getToolApprovalPolicy().decide(turn, call, tool)
                    : decisionFromRecord(policyRecord);
            } catch (RuntimeException policyError) {
                return runner.handleFailure(turn, response, policyError,
                    AgentTurnExecutionPoint.PROCESS_TOOLS);
            }
            if (decision == null) {
                return runner.handleFailure(turn, response,
                    new IllegalStateException("ToolApprovalPolicy returned null"),
                    AgentTurnExecutionPoint.PROCESS_TOOLS);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.REQUIRE_APPROVAL) {
                // 保存原 ToolCall 和 PROCESS_TOOLS 恢复点，批准后无需让模型重新生成参数。
                AgentSuspension suspension = AgentSuspension.toolApproval(
                    callKey(call), call.getName(), decision, ToolApprovalStage.POLICY,
                    turn.getExecutionPolicy().getApprovalTimeoutMillis());
                runner.suspend(turn, suspension);
                eventPublisher.notifyToolApprovalRequested(turn, call, decision);
                return AgentStepResult.of(response, results, null);
            }
            if (decision.getOutcome() == ToolApprovalDecision.Outcome.DENY) {
                // 拒绝是模型可消费的正常工具结果，不进入异常重试流程。
                ToolMessage rejected = buildToolRejectedMessage(turn, call, decision);
                appendToolResult(turn, rejected);
                results.add(rejected);
                continue;
            }

            // Tool 主动审批的拒绝记录优先于重新执行，避免再次进入其只读预检代码。
            ToolApprovalRecord toolRecord = turn.getToolApprovalRecord(
                callKey(call), ToolApprovalStage.TOOL);
            if (toolRecord != null && !toolRecord.isApproved()) {
                ToolMessage rejected = buildToolRejectedMessage(
                    turn, call, decisionFromRecord(toolRecord));
                appendToolResult(turn, rejected);
                results.add(rejected);
                continue;
            }

            if (tool.getExecutionTarget() == ToolExecutionTarget.EXTERNAL) {
                // 外部工具只登记请求并挂起；浏览器或其他执行器稍后通过 ResumeCommand 回传结果。
                turn.incrementToolCallCount();
                AgentSuspension suspension = AgentSuspension.externalTool(
                    callKey(call), call.getName(), call.getArguments(), tool.getMetadata(),
                    turn.getExecutionPolicy().getExternalToolTimeoutMillis());
                runner.suspend(turn, suspension);
                eventPublisher.notifyExternalToolRequested(turn, call, tool);
                return AgentStepResult.of(response, results, null);
            }

            eventPublisher.notifyToolStart(turn, call);
            ToolMessage completedResult;
            try {
                // 调用次数在进入业务工具前增加；控制流异常会在 catch 分支回滚。
                turn.incrementToolCallCount();
                completedResult = executeTool(
                    turn, tool, call, runnerOptions.getToolExecutor(), chatContext);
            } catch (AgentApprovalRequiredException request) {
                // Tool 主动审批发生在副作用前；记录请求指纹以阻止已批准请求形成审批循环。
                turn.rollbackToolCallCount();
                ToolApprovalRecord previous = turn.getToolApprovalRecord(
                    callKey(call), ToolApprovalStage.TOOL,
                    request.getDecision().getRequestFingerprint());
                if (isSameApprovedRequest(previous, request.getDecision())) {
                    IllegalArgumentException error = repeatedToolApproval(call, request);
                    eventPublisher.notifyToolError(turn, call, error);
                    return runner.handleFailure(turn, response, error,
                        AgentTurnExecutionPoint.PROCESS_TOOLS);
                }
                ToolApprovalDecision toolDecision = toolApprovalDecision(request.getDecision());
                AgentSuspension suspension = AgentSuspension.toolApproval(
                    callKey(call), call.getName(), toolDecision, ToolApprovalStage.TOOL,
                    turn.getExecutionPolicy().getApprovalTimeoutMillis());
                runner.suspend(turn, suspension);
                eventPublisher.notifyToolApprovalRequested(turn, call, toolDecision);
                return AgentStepResult.of(response, results, null);
            } catch (AgentFormRequiredException request) {
                // 表单请求同样属于控制流而非失败，本次未完成的工具调用不消耗调用预算。
                turn.rollbackToolCallCount();
                AgentFormDefinition form = request.getForm();
                AgentSuspension suspension = AgentSuspension.userInput(
                    callKey(call), request.getMessage(), form.getFormKey(), form.getSchema(),
                    call.getName(), AgentRunner.TOOL_INPUT_TARGET,
                    turn.getExecutionPolicy().getUserInputTimeoutMillis());
                runner.suspend(turn, suspension);
                eventPublisher.notifyToolInputRequested(turn, call, form);
                return AgentStepResult.of(response, results, null);
            } catch (RuntimeException error) {
                // Middleware/Interceptor 可能包装控制流异常，必须沿 cause 链恢复原始语义。
                AgentApprovalRequiredException approvalRequest = findCause(
                    error, AgentApprovalRequiredException.class);
                if (approvalRequest != null) {
                    turn.rollbackToolCallCount();
                    ToolApprovalRecord previous = turn.getToolApprovalRecord(
                        callKey(call), ToolApprovalStage.TOOL,
                        approvalRequest.getDecision().getRequestFingerprint());
                    if (isSameApprovedRequest(previous, approvalRequest.getDecision())) {
                        IllegalArgumentException repeated = repeatedToolApproval(call, error);
                        eventPublisher.notifyToolError(turn, call, repeated);
                        return runner.handleFailure(turn, response, repeated,
                            AgentTurnExecutionPoint.PROCESS_TOOLS);
                    }
                    ToolApprovalDecision toolDecision = toolApprovalDecision(
                        approvalRequest.getDecision());
                    AgentSuspension suspension = AgentSuspension.toolApproval(
                        callKey(call), call.getName(), toolDecision, ToolApprovalStage.TOOL,
                        turn.getExecutionPolicy().getApprovalTimeoutMillis());
                    runner.suspend(turn, suspension);
                    eventPublisher.notifyToolApprovalRequested(turn, call, toolDecision);
                    return AgentStepResult.of(response, results, null);
                }
                AgentFormRequiredException formRequest = findCause(
                    error, AgentFormRequiredException.class);
                if (formRequest != null) {
                    turn.rollbackToolCallCount();
                    AgentFormDefinition form = formRequest.getForm();
                    AgentSuspension suspension = AgentSuspension.userInput(
                        callKey(call), formRequest.getMessage(), form.getFormKey(),
                        form.getSchema(), call.getName(), AgentRunner.TOOL_INPUT_TARGET,
                        turn.getExecutionPolicy().getUserInputTimeoutMillis());
                    runner.suspend(turn, suspension);
                    eventPublisher.notifyToolInputRequested(turn, call, form);
                    return AgentStepResult.of(response, results, null);
                }
                eventPublisher.notifyToolError(turn, call, error);
                if (turn.getExecutionPolicy().getToolErrorStrategy()
                    == ToolErrorStrategy.RETURN_ERROR_TO_MODEL) {
                    // 配置允许时把普通异常转换为结构化 ToolMessage，让模型选择解释或替代方案。
                    completedResult = buildToolErrorMessage(turn, call, error);
                } else {
                    return runner.handleFailure(turn, response, error,
                        AgentTurnExecutionPoint.PROCESS_TOOLS);
                }
            }
            // Snapshot 保存异常必须直接向上传播，不能被误判为业务工具失败。
            appendToolResult(turn, completedResult);
            results.add(completedResult);
            eventPublisher.notifyToolEnd(turn, call);
            runner.refreshCancellation(turn);
        }

        // 本轮全部工具协议已经闭合，下一 Step 才能再次调用模型读取结果。
        turn.moveTo(AgentTurnExecutionPoint.INVOKE_MODEL);
        runner.saveSnapshot(turn);
        return AgentStepResult.of(response, results, null);
    }

    /**
     * 按顺序路径提交一个工具结果，并立即保存可恢复 Snapshot。
     *
     * <p>写入消息、移除队首 pending ToolCall 和保存 Snapshot 构成运行时确认边界。业务工具如有
     * 外部副作用，仍应使用稳定 ToolCall ID 自行实现幂等。</p>
     *
     * @param turn   接收结果的 Turn
     * @param result 已关联当前 ToolCall ID 的结果消息
     */
    private void appendToolResult(AgentTurn turn, ToolMessage result) {
        turn.getPrompt().addMessage(result);
        turn.removeFirstPendingToolCall();
        runner.saveSnapshot(turn);
    }

    /**
     * 并行执行一批已经被中央策略允许的本地 ToolCall。
     *
     * <p>并行任务提交后无法可靠撤销，因此即使某个工具失败或请求审批，也会等待当前批次全部收束，
     * 保存其他已经成功的结果后再决定失败或挂起。结果提交严格遵循模型声明顺序，而不是线程完成顺序，
     * 保证 Prompt 中 ToolCall/ToolMessage 的协议顺序稳定。</p>
     *
     * <p>只有全部调用都是可解析、已通过中央策略的本地业务工具，且调用预算允许整批执行时才进入
     * 并行路径。返回 {@code null} 表示前置条件不满足，调用方应回退到顺序路径；返回非空结果则表示
     * 本方法已经完整处理本批次或完成相应生命周期转换。</p>
     *
     * @param turn        当前包含多个 pending ToolCall 的 Turn
     * @param response    产生本批调用的模型响应；恢复路径可以为空
     * @param chatContext 需要传播到每个工具执行线程的模型上下文
     * @return 已处理结果；不适合并行执行时返回 {@code null}
     */
    private AgentStepResult executePendingToolsInParallel(AgentTurn turn,
                                                          AiMessageResponse response,
                                                          ChatContext chatContext) {
        // 第一遍只做无副作用的资格检查，任何调用不适合并行时整批回退，避免半批已经启动。
        List<ToolCall> calls = turn.getPendingToolCalls();
        List<Tool> tools = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            Tool tool = resolveTool(turn, call);
            if (tool == null || AgentUserInputTool.isUserInputTool(tool)
                || tool.getExecutionTarget() == ToolExecutionTarget.EXTERNAL) {
                return null;
            }
            ToolApprovalDecision decision;
            try {
                ToolApprovalRecord policyRecord = turn.getToolApprovalRecord(
                    callKey(call), ToolApprovalStage.POLICY);
                decision = policyRecord == null
                    ? turn.getAgent().getToolApprovalPolicy().decide(turn, call, tool)
                    : decisionFromRecord(policyRecord);
            } catch (RuntimeException policyError) {
                return runner.handleFailure(turn, response, policyError,
                    AgentTurnExecutionPoint.PROCESS_TOOLS);
            }
            if (decision == null
                || decision.getOutcome() != ToolApprovalDecision.Outcome.ALLOW) {
                return null;
            }
            ToolApprovalRecord toolRecord = turn.getToolApprovalRecord(
                callKey(call), ToolApprovalStage.TOOL);
            if (toolRecord != null && !toolRecord.isApproved()) return null;
            tools.add(tool);
        }
        // 通用预算超限可直接终止；工具次数不足以容纳整批时回退顺序路径，让其逐项精确截断。
        String budgetReason = runner.budgetExceededReason(turn, false);
        if (budgetReason != null) return runner.budgetExceeded(turn, budgetReason);
        if (turn.getExecutionPolicy().getBudget().getMaxToolCalls() > 0
            && turn.getToolCallCount() + calls.size()
            > turn.getExecutionPolicy().getBudget().getMaxToolCalls()) {
            return null;
        }

        // 协调线程池只限制本批并发量；单个业务调用仍由 Runner 配置的 toolExecutor 执行。
        int maxConcurrency = Math.min(calls.size(),
            turn.getExecutionPolicy().getMaxParallelToolCalls());
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "agent-tool-parallel-" + turn.getId());
            thread.setDaemon(true);
            return thread;
        });
        List<Future<ToolMessage>> futures = new ArrayList<>(calls.size());
        try {
            for (int index = 0; index < calls.size(); index++) {
                ToolCall call = calls.get(index);
                Tool tool = tools.get(index);
                turn.incrementToolCallCount();
                eventPublisher.notifyToolStart(turn, call);
                futures.add(executor.submit(() -> executeTool(
                    turn, tool, call, runnerOptions.getToolExecutor(), chatContext)));
            }

            // 等待并分类全部结果。控制流请求不发布 TOOL_FAILED，也不占用已完成工具次数。
            List<ToolMessage> results = new ArrayList<>(calls.size());
            RuntimeException firstFailure = null;
            List<RuntimeException> failures = new ArrayList<>(calls.size());
            List<AgentApprovalRequiredException> approvalRequests =
                new ArrayList<>(calls.size());
            List<AgentFormRequiredException> formRequests = new ArrayList<>(calls.size());
            for (int index = 0; index < calls.size(); index++) {
                ToolCall call = calls.get(index);
                try {
                    ToolMessage result = futures.get(index).get();
                    results.add(result);
                    failures.add(null);
                    approvalRequests.add(null);
                    formRequests.add(null);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return runner.handleFailure(turn, response,
                        new IllegalStateException(
                            "parallel tool execution was interrupted", error),
                        AgentTurnExecutionPoint.PROCESS_TOOLS);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    AgentApprovalRequiredException approvalRequest = findCause(
                        cause, AgentApprovalRequiredException.class);
                    AgentFormRequiredException formRequest = findCause(
                        cause, AgentFormRequiredException.class);
                    if (approvalRequest != null || formRequest != null) {
                        turn.rollbackToolCallCount();
                        results.add(null);
                        failures.add(null);
                        approvalRequests.add(approvalRequest);
                        formRequests.add(formRequest);
                        continue;
                    }
                    RuntimeException failure = cause instanceof RuntimeException
                        ? (RuntimeException) cause
                        : new IllegalStateException("parallel tool execution failed", cause);
                    eventPublisher.notifyToolError(turn, call, failure);
                    if (firstFailure == null) firstFailure = failure;
                    failures.add(failure);
                    results.add(null);
                    approvalRequests.add(null);
                    formRequests.add(null);
                }
            }

            // 按 ToolCall 原顺序提交成功结果和允许回传模型的错误，避免并发完成顺序污染 Prompt。
            List<ToolMessage> returned = new ArrayList<>(calls.size());
            boolean returnErrors = turn.getExecutionPolicy().getToolErrorStrategy()
                == ToolErrorStrategy.RETURN_ERROR_TO_MODEL
                || turn.getExecutionPolicy().getParallelFailureStrategy()
                == AgentParallelFailureStrategy.RETURN_ERRORS_TO_MODEL;
            for (int index = 0; index < calls.size(); index++) {
                ToolCall call = calls.get(index);
                ToolMessage result = results.get(index);
                if (result == null) {
                    if (approvalRequests.get(index) != null
                        || formRequests.get(index) != null) {
                        continue;
                    }
                    if (returnErrors) {
                        result = buildToolErrorMessage(turn, call, failures.get(index));
                        appendParallelToolResult(turn, call, result);
                        returned.add(result);
                    }
                    continue;
                }
                appendParallelToolResult(turn, call, result);
                returned.add(result);
                eventPublisher.notifyToolEnd(turn, call);
            }
            if (firstFailure != null && !returnErrors) {
                // 成功项已经分别保存；失败项仍留在 pending 中，以便重试从正确边界继续。
                return runner.handleFailure(turn, response, firstFailure,
                    AgentTurnExecutionPoint.PROCESS_TOOLS);
            }

            // 同一 Turn 只能暴露一个 Suspension，选择模型顺序中最靠前的控制流请求。
            for (int index = 0; index < calls.size(); index++) {
                ToolCall call = calls.get(index);
                AgentApprovalRequiredException approvalRequest = approvalRequests.get(index);
                if (approvalRequest != null) {
                    ToolApprovalRecord previous = turn.getToolApprovalRecord(
                        callKey(call), ToolApprovalStage.TOOL,
                        approvalRequest.getDecision().getRequestFingerprint());
                    if (isSameApprovedRequest(previous, approvalRequest.getDecision())) {
                        return runner.handleFailure(turn, response,
                            repeatedToolApproval(call, approvalRequest),
                            AgentTurnExecutionPoint.PROCESS_TOOLS);
                    }
                    ToolApprovalDecision toolDecision = toolApprovalDecision(
                        approvalRequest.getDecision());
                    AgentSuspension suspension = AgentSuspension.toolApproval(
                        callKey(call), call.getName(), toolDecision, ToolApprovalStage.TOOL,
                        turn.getExecutionPolicy().getApprovalTimeoutMillis());
                    runner.suspend(turn, suspension);
                    eventPublisher.notifyToolApprovalRequested(turn, call, toolDecision);
                    return AgentStepResult.of(response, returned, null);
                }
                AgentFormRequiredException formRequest = formRequests.get(index);
                if (formRequest != null) {
                    AgentFormDefinition form = formRequest.getForm();
                    AgentSuspension suspension = AgentSuspension.userInput(
                        callKey(call), formRequest.getMessage(), form.getFormKey(),
                        form.getSchema(), call.getName(), AgentRunner.TOOL_INPUT_TARGET,
                        turn.getExecutionPolicy().getUserInputTimeoutMillis());
                    runner.suspend(turn, suspension);
                    eventPublisher.notifyToolInputRequested(turn, call, form);
                    return AgentStepResult.of(response, returned, null);
                }
            }
            // 没有失败或挂起请求时，本轮工具协议已全部闭合，可以回到模型执行点。
            turn.moveTo(AgentTurnExecutionPoint.INVOKE_MODEL);
            runner.saveSnapshot(turn);
            return AgentStepResult.of(response, returned, null);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 提交并行批次中的单个结果。
     *
     * <p>并行执行期间 pending 列表的队首不一定与当前结果对应，因此必须按稳定 ToolCall ID 删除。
     * 每个结果独立保存 Snapshot，使进程在批次提交中途退出时也不会重复已经确认的工具调用。</p>
     *
     * @param turn   接收结果的 Turn
     * @param call   结果对应的原始 ToolCall
     * @param result 已完成的工具结果消息
     */
    private void appendParallelToolResult(AgentTurn turn, ToolCall call,
                                          ToolMessage result) {
        turn.getPrompt().addMessage(result);
        turn.removePendingToolCall(callKey(call));
        runner.saveSnapshot(turn);
    }

    /**
     * 从当前 Turn 绑定的 Agent 定义中解析可执行工具。
     *
     * <p>Snapshot 只持久化 ToolCall 的名称和参数，不保存 Tool 实例；恢复后必须由相同 Agent 版本的
     * 静态工具集合或动态 Resolver 重新解析。</p>
     *
     * @param turn 当前 Turn，用于访问绑定 Agent 及动态解析上下文
     * @param call 模型生成或从 Snapshot 恢复的工具调用
     * @return 当前 Turn 可执行的工具；调用为空、工具不存在或动态策略拒绝时返回 {@code null}
     */
    Tool resolveTool(AgentTurn turn, ToolCall call) {
        return call == null ? null : turn.getAgent().resolveTool(turn, call.getName());
    }

    /**
     * 构造本轮工具执行需要传播的 ChatContext。
     *
     * <p>正常模型调用优先复用响应中的完整上下文。Snapshot 恢复或自定义模型没有返回有效 Options 时，
     * 从 Turn 的 Prompt、streaming 配置和有效 ChatOptions 重建上下文，并补充 conversationId 与 turnId，
     * 使 Tool、ToolInterceptor 和下游客户端在两条路径中看到一致的关联信息。</p>
     *
     * @param turn     当前 Turn
     * @param response 本轮模型响应；恢复工具阶段时可以为空
     * @return 可绑定到工具执行线程的上下文
     */
    private ChatContext chatContextForToolExecution(AgentTurn turn,
                                                    AiMessageResponse response) {
        ChatContext responseContext = response == null ? null : response.getContext();
        if (responseContext != null && responseContext.getOptions() != null) {
            return responseContext;
        }

        ChatContext context = new ChatContext();
        if (responseContext != null) {
            context.setPrompt(responseContext.getPrompt());
            context.setConfig(responseContext.getConfig());
            context.setRequestSpec(responseContext.getRequestSpec());
            context.setStreaming(responseContext.isStreaming());
        } else {
            context.setPrompt(turn.getPrompt());
            context.setStreaming(turn.isStreaming());
        }
        // 优先使用 Turn 级覆盖，但复制语义由 Turn/Agent 配置边界负责，这里只补关联字段。
        ChatOptions options = turn.getChatOptionsOverride();
        if (options == null) options = turn.getAgent().getChatOptions();
        if (options == null) options = new ChatOptions();
        if (StringUtil.hasText(turn.getConversationId())) {
            options.setContextConversationId(turn.getConversationId());
        }
        options.setContextTurnId(turn.getId());
        context.setOptions(options);
        return context;
    }

    /**
     * 执行单个本地工具，并把任意 Java 返回值规范化为 ToolMessage。
     *
     * <p>方法创建跨恢复稳定的 {@link AgentToolContext}，将审批记录、表单数据、重试信息、执行次数、
     * 取消检查和进度发布能力注入工具调用链。工具返回的标量直接转为字符串，结构化对象统一序列化为
     * JSON；结果长度最后按 Turn 执行策略执行截断或拒绝。</p>
     *
     * @param turn        当前 Turn
     * @param tool        已解析且执行目标为本地的工具
     * @param call        当前原始 ToolCall
     * @param executor    Runner 配置的实际工具执行器
     * @param chatContext 需要传播到执行线程的聊天上下文
     * @return 已关联稳定 ToolCall ID 的模型可见结果
     */
    private ToolMessage executeTool(AgentTurn turn, Tool tool, ToolCall call,
                                    java.util.concurrent.Executor executor,
                                    ChatContext chatContext) {
        List<ToolInterceptor> interceptors = turn.getAgent().getToolInterceptors();
        AgentToolProgressEmitter progressEmitter = (message, data) ->
            eventPublisher.notifyToolProgress(turn, call, tool.getName(), message, data);

        // 执行次数在真正进入调用链前增加，恢复后的重放因此可以被业务工具明确识别。
        String toolCallId = callKey(call);
        int executionAttempt = turn.incrementToolExecutionAttempt(toolCallId);
        AgentToolContext toolContext = new AgentToolContext(
            turn.getId(), turn.getAgent().getId(), turn.getAgent().getVersion(), tool, call,
            toolCallId, progressEmitter, turn::isCancellationRequested,
            turn.getToolInputData(toolCallId), executionAttempt,
            turn.getToolResumeInfo(toolCallId),
            turn.getToolApprovalRecord(toolCallId, ToolApprovalStage.POLICY),
            turn.getToolApprovalRecord(toolCallId, ToolApprovalStage.TOOL),
            turn.getToolApprovalRecordsByRequest(toolCallId));

        AgentMiddlewareContext middlewareContext =
            AgentMiddlewareContext.forToolCall(runner, turn, toolContext);
        // Middleware、Core ToolInterceptor 和工具函数都在受控执行器及同一 ChatContext 中运行。
        Object value = executeToolCallWithTimeout(
            turn, middlewareContext, interceptors, executor, chatContext);
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
        // 在写入 Prompt 前限制结果大小，防止超大工具结果污染 Snapshot 和下一次模型输入。
        long maxCharacters = turn.getExecutionPolicy().getToolResultMaxCharacters();
        if (maxCharacters > 0 && result.getContent() != null
            && result.getContent().length() > maxCharacters) {
            if (turn.getExecutionPolicy().getToolResultOverflowStrategy()
                == AgentToolResultOverflowStrategy.TRUNCATE) {
                result.setContent(truncateToolResult(result.getContent(), maxCharacters));
            } else {
                throw new IllegalStateException(
                    "tool result exceeded maximum size: " + maxCharacters);
            }
        }
        return result;
    }

    /**
     * 在指定 Executor 中运行工具 Middleware 链，并同步等待完成或超时。
     *
     * <p>即使未配置超时，也统一通过 Executor 执行，以保持线程池隔离和上下文传播语义。超时会尝试
     * 中断任务并转换为稳定的运行时异常；被调用工具仍应自行响应线程中断并保证副作用幂等。</p>
     *
     * @param turn         提供工具超时策略的 Turn
     * @param context      当前工具 Middleware 上下文
     * @param interceptors Core ToolExecutor 使用的拦截器列表
     * @param executor     实际承载工具调用的执行器
     * @param chatContext  工具线程执行期间绑定的 ChatContext
     * @return 工具调用链的原始 Java 返回值
     */
    private Object executeToolCallWithTimeout(AgentTurn turn,
                                              AgentMiddlewareContext context,
                                              List<ToolInterceptor> interceptors,
                                              java.util.concurrent.Executor executor,
                                              ChatContext chatContext) {
        long timeout = turn.getExecutionPolicy().getToolExecutionTimeoutMillis();
        FutureTask<Object> task = new FutureTask<>(() -> {
            // Executor 线程可能被复用，必须在 finally 中恢复其原有上下文，避免跨 Turn 泄漏。
            ChatContext previous = ChatContextHolder.currentContext();
            try {
                if (chatContext != null) ChatContextHolder.set(chatContext);
                return proceedToolCall(context, 0, interceptors);
            } finally {
                if (previous == null) {
                    ChatContextHolder.clear();
                } else {
                    ChatContextHolder.set(previous);
                }
            }
        });
        executor.execute(task);
        try {
            return timeout <= 0 ? task.get() : task.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            throw new IllegalStateException(
                "tool execution exceeded timeout: " + timeout + "ms", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool execution was interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("tool execution failed", cause);
        }
    }

    /**
     * 递归构造 Agent 级工具 Middleware 责任链，链尾交给 Core ToolExecutor。
     *
     * <p>Agent Middleware 位于 Core ToolInterceptor 外层；每个 Middleware 应至多调用一次 next。
     * 受控 {@link AgentToolContext} 通过 ToolExecutor attributes 注入，不会混入模型可见参数 Schema。</p>
     *
     * @param context      当前责任链上下文
     * @param index        即将执行的 Agent Middleware 下标
     * @param interceptors Core 工具拦截器
     * @return Middleware 链或最终工具函数的返回值
     */
    private Object proceedToolCall(AgentMiddlewareContext context, int index,
                                   List<ToolInterceptor> interceptors) {
        List<AgentMiddleware> middlewares = context.getRun().getAgent().getMiddlewares();
        if (index >= middlewares.size()) {
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
     * 使用执行策略配置的工厂把普通工具异常转换为模型可见 ToolMessage。
     *
     * <p>业务工厂只决定消息内容；处理器始终覆盖 ToolCall ID，保证返回消息与原始调用正确关联。</p>
     *
     * @param turn  当前 Turn
     * @param call  失败的工具调用
     * @param error 原始异常
     * @return 非空且已关联 ToolCall ID 的错误消息
     */
    private ToolMessage buildToolErrorMessage(AgentTurn turn, ToolCall call,
                                              Throwable error) {
        ToolMessage result = turn.getExecutionPolicy().getToolErrorMessageFactory()
            .create(turn, call, error);
        if (result == null) {
            throw new IllegalStateException("ToolErrorMessageFactory must not return null");
        }
        result.setToolCallId(callKey(call));
        return result;
    }

    /**
     * 将中央策略拒绝或人工拒绝转换为结构化工具结果。
     *
     * <p>恢复命令中的拒绝原因优先，其次使用当前审批决定，最后回退到已保存审计信息。拒绝作为
     * 正常 ToolMessage 返回模型，不触发工具失败重试。</p>
     *
     * @param turn     包含恢复理由和审批审计记录的 Turn
     * @param call     被拒绝的 ToolCall
     * @param decision 当前有效审批决定
     * @return JSON 内容的结构化拒绝消息
     */
    private ToolMessage buildToolRejectedMessage(AgentTurn turn, ToolCall call,
                                                 ToolApprovalDecision decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", true);
        body.put("type", "tool_rejected");
        Object resumedReason = turn.getMetadata().get(
            "toolRejectionReason." + callKey(call));
        Object approvalAudit = turn.getMetadata().get(
            "toolApprovalAudit." + callKey(call));
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
     * 为工具主动产生的审批请求附加框架可信来源标记。
     *
     * <p>业务 metadata 先复制，框架保留键最后覆盖，防止业务内容伪装审批来源。请求指纹保持不变，
     * 供恢复后判断相同请求是否已获批准。</p>
     *
     * @param source Tool 在只读预检后给出的审批决定
     * @return 带可信来源标记的新决定
     */
    private ToolApprovalDecision toolApprovalDecision(ToolApprovalDecision source) {
        return ToolApprovalDecision.requireApproval()
            .code(source.getCode())
            .message(source.getMessage())
            .reason(source.getReason())
            .metadata(source.getMetadata())
            .metadata(APPROVAL_TRIGGER_METADATA, TOOL_APPROVAL_TRIGGER)
            .requestFingerprint(source.getRequestFingerprint())
            .build();
    }

    /**
     * 将持久化审批记录还原为执行阶段可复用的审批决定。
     *
     * @param record Snapshot 中保存的审批记录
     * @return 保留审批结果、理由、元数据和请求指纹的决定
     */
    private ToolApprovalDecision decisionFromRecord(ToolApprovalRecord record) {
        ToolApprovalDecision.Builder builder = record.isApproved()
            ? ToolApprovalDecision.allow() : ToolApprovalDecision.deny();
        String rejectionMessage = StringUtil.hasText(record.getRejectionReason())
            ? record.getRejectionReason() : record.getMessage();
        return builder.code(record.getCode())
            .message(rejectionMessage)
            .reason(record.getReason())
            .metadata(record.getRequestMetadata())
            .requestFingerprint(record.getRequestFingerprint())
            .build();
    }

    /**
     * 沿异常 cause 链查找指定类型的控制流异常。
     *
     * <p>使用身份集合检测循环引用，避免第三方 Middleware 构造异常环后导致无限遍历。</p>
     *
     * @param error 起始异常
     * @param type  需要查找的异常类型
     * @param <T>   目标异常类型
     * @return 首个匹配异常；不存在时返回 {@code null}
     */
    private <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Set<Throwable> visited = Collections.newSetFromMap(
            new IdentityHashMap<Throwable, Boolean>());
        Throwable current = error;
        while (current != null && visited.add(current)) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }

    /**
     * 判断 Tool 当前再次提出的请求是否与已经批准的请求完全相同。
     *
     * @param record   已保存的 Tool 阶段审批记录
     * @param decision Tool 本次抛出的审批决定
     * @return 已批准且请求指纹相同时返回 {@code true}
     */
    private boolean isSameApprovedRequest(ToolApprovalRecord record,
                                          ToolApprovalDecision decision) {
        return record != null && record.isApproved() && decision != null
            && Objects.equals(record.getRequestFingerprint(),
            decision.getRequestFingerprint());
    }

    /**
     * 构造相同审批请求在批准后再次出现时的协议错误。
     *
     * @param call  反复申请审批的 ToolCall
     * @param cause Tool 抛出的原始控制流异常
     * @return 不应自动重试的参数类异常
     */
    private IllegalArgumentException repeatedToolApproval(ToolCall call,
                                                          Throwable cause) {
        return new IllegalArgumentException(
            "Tool requested the same approval after it was already approved: "
                + call.getName(), cause);
    }

    /**
     * 返回 ToolCall 的稳定关联键。
     *
     * <p>优先使用模型提供或 Runner 补齐的调用 ID；兼容旧模型响应时回退到工具名。</p>
     *
     * @param call 非空 ToolCall
     * @return 可用于 pending、审批、表单和恢复数据关联的键
     */
    static String callKey(ToolCall call) {
        return StringUtil.hasText(call.getId()) ? call.getId() : call.getName();
    }

    /**
     * 为模型未提供 ID 的 ToolCall 补充当前 Turn 内唯一且可持久化的 ID。
     *
     * <p>ID 在 AI 消息进入 Prompt 和 Snapshot 之前生成，由 turnId、模型迭代次数和调用序号组成，
     * 因而审批挂起、工具结果和跨进程恢复可以稳定关联同一次调用。</p>
     *
     * @param turn    当前 Turn
     * @param message 尚未写入 Prompt 的模型消息
     */
    void ensureToolCallIds(AgentTurn turn, AiMessage message) {
        if (message == null || !message.hasToolCalls()) return;
        int index = 0;
        for (ToolCall call : message.getToolCalls()) {
            if (!StringUtil.hasText(call.getId())) {
                call.setId(turn.getId() + "-" + turn.getIterationCount() + "-" + index);
            }
            index++;
        }
    }

    /**
     * 将工具结果限制在指定字符数内，并尽量保留明确的截断标记。
     *
     * <p>配置使用 long，而 Java String 长度使用 int，因此先安全收窄。截断点若正好位于 UTF-16
     * 代理项中间，会向前移动一个字符，避免生成无效 Unicode 文本。</p>
     *
     * @param content       原始工具结果
     * @param maxCharacters 允许的最大 Java 字符数
     * @return 未超限的原文本，或带截断标记的安全前缀
     */
    static String truncateToolResult(String content, long maxCharacters) {
        int limit = (int) Math.min((long) Integer.MAX_VALUE, maxCharacters);
        if (content.length() <= limit) return content;
        String marker = "\n...[tool result truncated]";
        if (limit <= marker.length()) return content.substring(0, limit);
        int end = limit - marker.length();
        if (end > 0 && end < content.length()
            && Character.isHighSurrogate(content.charAt(end - 1))
            && Character.isLowSurrogate(content.charAt(end))) {
            end--;
        }
        return content.substring(0, end) + marker;
    }
}

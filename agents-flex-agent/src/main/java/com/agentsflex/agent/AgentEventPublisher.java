/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventListener;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.task.AgentTask;
import com.agentsflex.agent.task.AgentTaskPlan;
import com.agentsflex.agent.task.AgentTaskStatus;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 构造 Agent 运行事件，并在当前进程内同步发布给已注册监听器。
 *
 * <p>该类是 {@link AgentRunner} 的包内实现细节，不提供持久化或异步消息能力。监听器异常会被
 * 隔离；事件序号只在当前 Publisher 实例内按 runId 递增。</p>
 */
final class AgentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentEventPublisher.class);

    /** 观察统一不可变事件的线程安全监听器列表。 */
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    /** 为每个活动 Run 分配进程内事件序号。 */
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    void addListener(AgentEventListener listener) {
        if (listener != null) listeners.add(listener);
    }

    void removeListener(AgentEventListener listener) {
        listeners.remove(listener);
    }

    void clearSequence(String runId) {
        if (runId != null) sequences.remove(runId);
    }

    void notifyRunStart(AgentRun run) {
        publish(run, AgentEventType.RUN_STARTED, null);
    }

    void notifyModelStart(AgentRun run) {
        publish(run, AgentEventType.MODEL_STARTED, iterationAttributes(run));
    }

    void notifyModelEnd(AgentRun run, AiMessageResponse response) {
        Map<String, Object> values = iterationAttributes(run);
        values.put("hasToolCalls",
            response != null && response.getMessage() != null
                && response.getMessage().hasToolCalls());
        publish(run, AgentEventType.MODEL_COMPLETED, values);
    }

    void notifyToolStart(AgentRun run, ToolCall call) {
        Map<String, Object> values = iterationAttributes(run);
        values.putAll(attributes("toolCallId", callKey(call), "toolName", call.getName()));
        publish(run, AgentEventType.TOOL_STARTED, values);
    }

    void notifyToolEnd(AgentRun run, ToolCall call) {
        Map<String, Object> values = iterationAttributes(run);
        values.putAll(attributes("toolCallId", callKey(call), "toolName", call.getName()));
        publish(run, AgentEventType.TOOL_COMPLETED, values);
    }

    void notifyToolError(AgentRun run, ToolCall call, Throwable error) {
        publish(run, AgentEventType.TOOL_FAILED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "error", errorMessage(error)));
    }

    void notifyToolProgress(AgentRun run, ToolCall call, String toolName,
                            String message, Map<String, ?> data) {
        Map<String, Object> values = attributes("toolCallId", callKey(call),
            "toolName", toolName, "message", message);
        if (data != null) values.putAll(data);
        publish(run, AgentEventType.TOOL_PROGRESS, values);
    }

    void notifyRunComplete(AgentRun run) {
        publish(run, AgentEventType.RUN_COMPLETED, null);
    }

    void notifyRunFailed(AgentRun run, Throwable error) {
        publish(run, AgentEventType.RUN_FAILED, attributes("error", errorMessage(error)));
    }

    void notifyRunCancelled(AgentRun run) {
        publish(run, AgentEventType.RUN_CANCELLED, null);
    }

    void notifyCancellationRequested(AgentRun run) {
        publish(run, AgentEventType.CANCELLATION_REQUESTED, null);
    }

    void notifyMaxIterationsReached(AgentRun run) {
        publish(run, AgentEventType.MAX_ITERATIONS_REACHED,
            attributes("iterations", run.getIterationCount()));
    }

    void notifyMaxStepsReached(AgentRun run) {
        publish(run, AgentEventType.MAX_STEPS_REACHED,
            attributes("steps", run.getStepCount(),
                "maxSteps", run.getExecutionPolicy().getMaxSteps()));
    }

    void notifySnapshotSaved(AgentRun run, AgentRunSnapshot snapshot) {
        publish(run, AgentEventType.SNAPSHOT_SAVED,
            attributes("version", snapshot.getState().getVersion(),
                "status", snapshot.getState().getStatus(),
                "phase", snapshot.getState().getPhase()));
    }

    void notifyRunSuspended(AgentRun run, AgentSuspension suspension) {
        publish(run, AgentEventType.RUN_SUSPENDED,
            attributes("suspensionType", suspension.getType(),
                "correlationId", suspension.getCorrelationId()));
    }

    void notifyRunResumed(AgentRun run, AgentResumeCommand command) {
        publish(run, AgentEventType.RUN_RESUMED,
            attributes("commandType", command.getType(),
                "correlationId", command.getCorrelationId()));
    }

    void notifyToolApprovalRequested(AgentRun run, ToolCall call,
                                     ToolApprovalDecision decision) {
        publish(run, AgentEventType.TOOL_APPROVAL_REQUESTED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "approvalOutcome", decision.getOutcome(), "approvalCode", decision.getCode(),
                "approvalMessage", decision.getMessage(), "approvalReason", decision.getReason(),
                "approvalMetadata", decision.getMetadata()));
    }

    void notifyRetryScheduled(AgentRun run, Throwable error) {
        publish(run, AgentEventType.RETRY_SCHEDULED,
            attributes("retryCount", run.getRetryCount(),
                "nextRunAt", run.getNextRunAt(), "error", errorMessage(error)));
    }

    void notifyBudgetExceeded(AgentRun run, String reason) {
        publish(run, AgentEventType.BUDGET_EXCEEDED, attributes("reason", reason));
    }

    void notifyChildStarted(AgentRun parent, AgentRun child) {
        publish(parent, AgentEventType.CHILD_STARTED,
            attributes("childRunId", child.getId(),
                "childAgentId", child.getAgent().getId()));
    }

    void notifyPlanCreated(AgentRun run, AgentTaskPlan plan) {
        publish(run, AgentEventType.PLAN_CREATED,
            attributes("planId", plan.getId(), "goal", plan.getGoal(),
                "taskCount", plan.getTasks().size()));
    }

    void notifyPlanUpdated(AgentRun run, AgentTaskPlan plan) {
        publish(run, AgentEventType.PLAN_UPDATED,
            attributes("planId", plan.getId(),
                "revisionCount", plan.getRevisionCount(),
                "reason", plan.getLastRevisionReason(),
                "taskCount", plan.getTasks().size()));
    }

    void notifyTaskStarted(AgentRun parent, AgentTask task, AgentRun child) {
        publish(parent, AgentEventType.TASK_STARTED,
            attributes("taskId", task.getId(), "title", task.getTitle(),
                "childRunId", child.getId(), "childAgentId", child.getAgent().getId()));
    }

    void notifyTaskFinished(AgentRun parent, AgentTask task, AgentRun child,
                            AgentTaskStatus status) {
        AgentEventType type = status == AgentTaskStatus.COMPLETED
            ? AgentEventType.TASK_COMPLETED : AgentEventType.TASK_FAILED;
        publish(parent, type,
            attributes("taskId", task.getId(), "childRunId", child.getId(),
                "taskStatus", status, "result", child.getFinalOutput(),
                "error", errorMessage(child.getError())));
    }

    /** 创建不可变事件并同步通知全部监听器；空 Run 不产生事件。 */
    void publish(AgentRun run, AgentEventType type, Map<String, ?> data) {
        if (run == null) return;
        Map<String, Object> values = attributes(
            "status", run.getStatus(),
            "phase", run.getPhase(),
            "stepCount", run.getStepCount(),
            "maxSteps", run.getExecutionPolicy().getMaxSteps());
        if (data != null) values.putAll(data);
        long sequence = sequences.computeIfAbsent(run.getId(), key -> new AtomicLong())
            .incrementAndGet();
        AgentEvent event = new AgentEvent(run.getId(), run.getRootRunId(),
            run.getParentRunId(), run.getAgent().getId(), run.getAgent().getVersion(),
            sequence, type, values);
        for (AgentEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException error) {
                log.warn("Agent event listener failed", error);
            }
        }
    }

    private Map<String, Object> iterationAttributes(AgentRun run) {
        int maxIterations = run.getExecutionPolicy().getMaxIterations();
        return attributes("iteration", run.getIterationCount(),
            "maxIterations", maxIterations,
            "remainingIterations", Math.max(0, maxIterations - run.getIterationCount()));
    }

    private Map<String, Object> attributes(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index] != null && values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return result;
    }

    private String callKey(ToolCall call) {
        return StringUtil.hasText(call.getId()) ? call.getId() : call.getName();
    }

    private String errorMessage(Throwable error) {
        return error == null ? null : error.getClass().getName() + ": " + error.getMessage();
    }
}

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
 * 隔离；事件序号只在当前 Publisher 实例内按 turnId 递增。</p>
 */
final class AgentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentEventPublisher.class);

    /**
     * 观察统一不可变事件的线程安全监听器列表。
     */
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    /**
     * 为每个活动 Turn 分配进程内事件序号。
     */
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    void addListener(AgentEventListener listener) {
        if (listener != null) listeners.add(listener);
    }

    void removeListener(AgentEventListener listener) {
        listeners.remove(listener);
    }

    void clearSequence(String turnId) {
        if (turnId != null) sequences.remove(turnId);
    }

    void notifyTurnStart(AgentTurn turn) {
        publish(turn, AgentEventType.TURN_STARTED, null);
    }

    void notifyModelStart(AgentTurn turn) {
        publish(turn, AgentEventType.MODEL_STARTED, iterationAttributes(turn));
    }

    void notifyModelEnd(AgentTurn turn, AiMessageResponse response) {
        Map<String, Object> values = iterationAttributes(turn);
        values.put("hasToolCalls",
            response != null && response.getMessage() != null
                && response.getMessage().hasToolCalls());
        publish(turn, AgentEventType.MODEL_COMPLETED, values);
    }

    void notifyToolStart(AgentTurn turn, ToolCall call) {
        Map<String, Object> values = iterationAttributes(turn);
        values.putAll(attributes("toolCallId", callKey(call), "toolName", call.getName()));
        publish(turn, AgentEventType.TOOL_STARTED, values);
    }

    void notifyToolEnd(AgentTurn turn, ToolCall call) {
        Map<String, Object> values = iterationAttributes(turn);
        values.putAll(attributes("toolCallId", callKey(call), "toolName", call.getName()));
        publish(turn, AgentEventType.TOOL_COMPLETED, values);
    }

    void notifyToolError(AgentTurn turn, ToolCall call, Throwable error) {
        publish(turn, AgentEventType.TOOL_FAILED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "error", errorMessage(error)));
    }

    void notifyToolProgress(AgentTurn turn, ToolCall call, String toolName,
                            String message, Map<String, ?> data) {
        Map<String, Object> values = attributes("toolCallId", callKey(call),
            "toolName", toolName, "message", message);
        if (data != null) values.putAll(data);
        publish(turn, AgentEventType.TOOL_PROGRESS, values);
    }

    void notifyTurnComplete(AgentTurn turn) {
        publish(turn, AgentEventType.TURN_COMPLETED, null);
    }

    void notifyTurnFailed(AgentTurn turn, Throwable error) {
        publish(turn, AgentEventType.TURN_FAILED, attributes("error", errorMessage(error)));
    }

    void notifyTurnCancelled(AgentTurn turn) {
        publish(turn, AgentEventType.TURN_CANCELLED, null);
    }

    void notifyCancellationRequested(AgentTurn turn) {
        publish(turn, AgentEventType.CANCELLATION_REQUESTED, null);
    }

    void notifyMaxIterationsReached(AgentTurn turn) {
        publish(turn, AgentEventType.MAX_ITERATIONS_REACHED,
            attributes("iterations", turn.getIterationCount()));
    }

    void notifyMaxStepsReached(AgentTurn turn) {
        publish(turn, AgentEventType.MAX_STEPS_REACHED,
            attributes("steps", turn.getStepCount(),
                "maxSteps", turn.getExecutionPolicy().getMaxSteps()));
    }

    void notifySnapshotSaved(AgentTurn turn, AgentTurnSnapshot snapshot) {
        publish(turn, AgentEventType.SNAPSHOT_SAVED,
            attributes("version", snapshot.getState().getVersion(),
                "status", snapshot.getState().getStatus(),
                "phase", snapshot.getState().getPhase()));
    }

    void notifyTurnSuspended(AgentTurn turn, AgentSuspension suspension) {
        publish(turn, AgentEventType.TURN_SUSPENDED,
            attributes("suspensionType", suspension.getType(),
                "correlationId", suspension.getCorrelationId(),
                "message", suspension.getMessage(),
                "resumePhase", suspension.getResumePhase(),
                "metadata", suspension.getMetadata()));
    }

    void notifyTurnResumed(AgentTurn turn, AgentResumeCommand command) {
        publish(turn, AgentEventType.TURN_RESUMED,
            attributes("commandType", command.getType(),
                "correlationId", command.getCorrelationId()));
    }

    void notifyToolApprovalRequested(AgentTurn turn, ToolCall call,
                                     ToolApprovalDecision decision) {
        publish(turn, AgentEventType.TOOL_APPROVAL_REQUESTED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "approvalOutcome", decision.getOutcome(), "approvalCode", decision.getCode(),
                "approvalMessage", decision.getMessage(), "approvalReason", decision.getReason(),
                "approvalMetadata", decision.getMetadata()));
    }

    void notifyRetryScheduled(AgentTurn turn, Throwable error) {
        publish(turn, AgentEventType.RETRY_SCHEDULED,
            attributes("retryCount", turn.getRetryCount(),
                "nextRunnableAt", turn.getNextRunnableAt(), "error", errorMessage(error)));
    }

    void notifyBudgetExceeded(AgentTurn turn, String reason) {
        publish(turn, AgentEventType.BUDGET_EXCEEDED, attributes("reason", reason));
    }

    void notifyChildStarted(AgentTurn parent, AgentTurn child) {
        publish(parent, AgentEventType.CHILD_STARTED,
            attributes("childTurnId", child.getId(),
                "childAgentId", child.getAgent().getId()));
    }

    void notifyPlanCreated(AgentTurn turn, AgentTaskPlan plan) {
        publish(turn, AgentEventType.PLAN_CREATED,
            attributes("planId", plan.getId(), "goal", plan.getGoal(),
                "taskCount", plan.getTasks().size()));
    }

    void notifyPlanUpdated(AgentTurn turn, AgentTaskPlan plan) {
        publish(turn, AgentEventType.PLAN_UPDATED,
            attributes("planId", plan.getId(),
                "revisionCount", plan.getRevisionCount(),
                "reason", plan.getLastRevisionReason(),
                "taskCount", plan.getTasks().size()));
    }

    void notifyTaskStarted(AgentTurn parent, AgentTask task, AgentTurn child) {
        publish(parent, AgentEventType.TASK_STARTED,
            attributes("taskId", task.getId(), "title", task.getTitle(),
                "childTurnId", child.getId(), "childAgentId", child.getAgent().getId()));
    }

    void notifyTaskFinished(AgentTurn parent, AgentTask task, AgentTurn child,
                            AgentTaskStatus status) {
        AgentEventType type = status == AgentTaskStatus.COMPLETED
            ? AgentEventType.TASK_COMPLETED : AgentEventType.TASK_FAILED;
        publish(parent, type,
            attributes("taskId", task.getId(), "childTurnId", child.getId(),
                "taskStatus", status, "result", child.getFinalOutput(),
                "error", errorMessage(child.getError())));
    }

    /**
     * 创建不可变事件并同步通知全部监听器；空 Turn 不产生事件。
     */
    void publish(AgentTurn turn, AgentEventType type, Map<String, ?> data) {
        if (turn == null) return;
        Map<String, Object> values = attributes(
            "status", turn.getStatus(),
            "phase", turn.getPhase(),
            "stepCount", turn.getStepCount(),
            "maxSteps", turn.getExecutionPolicy().getMaxSteps());
        if (data != null) values.putAll(data);
        long sequence = sequences.computeIfAbsent(turn.getId(), key -> new AtomicLong())
            .incrementAndGet();
        AgentEvent event = new AgentEvent(turn.getId(), turn.getRootTurnId(),
            turn.getParentTurnId(), turn.getAgent().getId(), turn.getAgent().getVersion(),
            sequence, type, values);
        for (AgentEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException error) {
                log.warn("Agent event listener failed", error);
            }
        }
    }

    private Map<String, Object> iterationAttributes(AgentTurn turn) {
        int maxIterations = turn.getExecutionPolicy().getMaxIterations();
        return attributes("iteration", turn.getIterationCount(),
            "maxIterations", maxIterations,
            "remainingIterations", Math.max(0, maxIterations - turn.getIterationCount()));
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

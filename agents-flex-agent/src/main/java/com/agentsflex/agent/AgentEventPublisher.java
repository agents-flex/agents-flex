/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.compression.AgentContextCompressionResult;
import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.event.AgentEventListener;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.tool.AgentFormDefinition;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
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

    /**
     * 注册进程内事件监听器；空监听器会被忽略。
     *
     * @param listener 需要接收后续事件的监听器
     */
    void addListener(AgentEventListener listener) {
        if (listener != null) listeners.add(listener);
    }

    /**
     * 移除已注册监听器；未注册的实例不会产生副作用。
     *
     * @param listener 待移除监听器
     */
    void removeListener(AgentEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * 在 Turn 生命周期结束后释放其进程内事件序号。
     *
     * @param turnId 已结束的 Turn ID；空值会被忽略
     */
    void clearSequence(String turnId) {
        if (turnId != null) sequences.remove(turnId);
    }

    /**
     * 发布 Turn 开始事件。
     *
     * @param turn 刚进入执行阶段的 Turn
     */
    void notifyTurnStart(AgentTurn turn) {
        publish(turn, AgentEventType.TURN_STARTED, null);
    }

    /**
     * 发布模型调用开始事件，并携带当前迭代预算。
     *
     * @param turn 当前 Turn
     */
    void notifyModelStart(AgentTurn turn) {
        publish(turn, AgentEventType.MODEL_STARTED, iterationAttributes(turn));
    }

    /**
     * 发布模型调用完成事件，并标识响应是否包含工具调用。
     *
     * @param turn     当前 Turn
     * @param response 模型响应；允许为空以记录不完整调用
     */
    void notifyModelEnd(AgentTurn turn, AiMessageResponse response) {
        Map<String, Object> values = iterationAttributes(turn);
        values.put("hasToolCalls",
            response != null && response.getMessage() != null
                && response.getMessage().hasToolCalls());
        publish(turn, AgentEventType.MODEL_COMPLETED, values);
    }

    void notifyContextCompressionStarted(AgentTurn turn, int historyMessageCount,
                                         int pendingMessageCount) {
        publish(turn, AgentEventType.CONTEXT_COMPRESSION_STARTED,
            attributes("historyMessageCount", historyMessageCount,
                "pendingMessageCount", pendingMessageCount));
    }

    void notifyContextCompressionCompleted(AgentTurn turn,
                                           AgentContextCompressionResult result) {
        publish(turn, AgentEventType.CONTEXT_COMPRESSION_COMPLETED,
            compressionAttributes(result));
    }

    void notifyContextCompressionSkipped(AgentTurn turn,
                                         AgentContextCompressionResult result) {
        publish(turn, AgentEventType.CONTEXT_COMPRESSION_SKIPPED,
            compressionAttributes(result));
    }

    void notifyContextCompressionFailed(AgentTurn turn, Throwable error) {
        publish(turn, AgentEventType.CONTEXT_COMPRESSION_FAILED,
            attributes("error", errorMessage(error)));
    }

    /**
     * 发布工具执行开始事件。
     *
     * @param turn 当前 Turn
     * @param call 即将执行的工具调用
     */
    void notifyToolStart(AgentTurn turn, ToolCall call) {
        Map<String, Object> values = iterationAttributes(turn);
        values.putAll(attributes("toolCallId", callKey(call), "toolName", call.getName()));
        publish(turn, AgentEventType.TOOL_STARTED, values);
    }

    /**
     * 发布工具执行成功完成事件。
     *
     * @param turn 当前 Turn
     * @param call 已完成的工具调用
     */
    void notifyToolEnd(AgentTurn turn, ToolCall call) {
        Map<String, Object> values = iterationAttributes(turn);
        values.putAll(attributes("toolCallId", callKey(call), "toolName", call.getName()));
        publish(turn, AgentEventType.TOOL_COMPLETED, values);
    }

    /**
     * 发布工具执行失败事件，并将异常类型和消息转为稳定文本。
     *
     * @param turn  当前 Turn
     * @param call  失败的工具调用
     * @param error 执行异常
     */
    void notifyToolError(AgentTurn turn, ToolCall call, Throwable error) {
        publish(turn, AgentEventType.TOOL_FAILED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "error", errorMessage(error)));
    }

    /**
     * 发布工具执行过程事件，并合并工具提供的结构化进度数据。
     *
     * @param turn     当前 Turn
     * @param call     工具调用
     * @param toolName 对外展示的工具名
     * @param message  进度说明
     * @param data     可选结构化进度数据
     */
    void notifyToolProgress(AgentTurn turn, ToolCall call, String toolName,
                            String message, Map<String, ?> data) {
        Map<String, Object> values = attributes("toolCallId", callKey(call),
            "toolName", toolName, "message", message);
        if (data != null) values.putAll(data);
        publish(turn, AgentEventType.TOOL_PROGRESS, values);
    }

    /**
     * 发布 Turn 正常完成事件。
     *
     * @param turn 已完成 Turn
     */
    void notifyTurnComplete(AgentTurn turn) {
        publish(turn, AgentEventType.TURN_COMPLETED, null);
    }

    /**
     * 发布 Turn 失败事件。
     *
     * @param turn  已失败 Turn
     * @param error 导致失败的异常
     */
    void notifyTurnFailed(AgentTurn turn, Throwable error) {
        publish(turn, AgentEventType.TURN_FAILED, attributes("error", errorMessage(error)));
    }

    /**
     * 发布 Turn 已取消事件。
     *
     * @param turn 已取消 Turn
     */
    void notifyTurnCancelled(AgentTurn turn) {
        publish(turn, AgentEventType.TURN_CANCELLED, null);
    }

    /**
     * 发布取消请求已登记事件，此时 Turn 可能尚未进入终态。
     *
     * @param turn 当前 Turn
     */
    void notifyCancellationRequested(AgentTurn turn) {
        publish(turn, AgentEventType.CANCELLATION_REQUESTED, null);
    }

    /**
     * 发布模型迭代次数耗尽事件，并记录实际次数。
     *
     * @param turn 已终止 Turn
     */
    void notifyMaxIterationsReached(AgentTurn turn) {
        publish(turn, AgentEventType.MAX_ITERATIONS_REACHED,
            attributes("iterations", turn.getIterationCount()));
    }

    /**
     * 发布 Runner 步骤预算耗尽事件，并记录实际值与上限。
     *
     * @param turn 已终止 Turn
     */
    void notifyMaxStepsReached(AgentTurn turn) {
        publish(turn, AgentEventType.MAX_STEPS_REACHED,
            attributes("steps", turn.getStepCount(),
                "maxSteps", turn.getExecutionPolicy().getMaxSteps()));
    }

    /**
     * 发布 Snapshot 保存成功事件，携带持久化后的版本和生命周期状态。
     *
     * @param turn     Snapshot 所属 Turn
     * @param snapshot Store 返回的已保存快照
     */
    void notifySnapshotSaved(AgentTurn turn, AgentTurnSnapshot snapshot) {
        publish(turn, AgentEventType.SNAPSHOT_SAVED,
            attributes("version", snapshot.getState().getVersion(),
                "status", snapshot.getState().getStatus(),
                "executionPoint", snapshot.getState().getExecutionPoint()));
    }

    /**
     * 发布 Turn 挂起事件，并完整携带恢复所需的关联信息。
     *
     * @param turn       已挂起 Turn
     * @param suspension 挂起原因及恢复阶段
     */
    void notifyTurnSuspended(AgentTurn turn, AgentSuspension suspension) {
        publish(turn, AgentEventType.TURN_SUSPENDED,
            attributes("suspensionType", suspension.getType(),
                "correlationId", suspension.getCorrelationId(),
                "message", suspension.getMessage(),
                "resumeExecutionPoint", suspension.getResumeExecutionPoint(),
                "metadata", suspension.getMetadata()));
    }

    /**
     * 发布 Turn 恢复事件，记录命令类型和关联 ID。
     *
     * @param turn    已恢复 Turn
     * @param command 已应用的恢复命令
     */
    void notifyTurnResumed(AgentTurn turn, AgentResumeCommand command) {
        publish(turn, AgentEventType.TURN_RESUMED,
            attributes("commandType", command.getType(),
                "correlationId", command.getCorrelationId()));
    }

    /**
     * 发布工具审批请求，保留策略决策代码、原因和业务元数据。
     *
     * @param turn     等待审批的 Turn
     * @param call     待审批工具调用
     * @param decision 审批策略决策
     */
    void notifyToolApprovalRequested(AgentTurn turn, ToolCall call,
                                     ToolApprovalDecision decision) {
        publish(turn, AgentEventType.TOOL_APPROVAL_REQUESTED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "approvalOutcome", decision.getOutcome(), "approvalCode", decision.getCode(),
                "approvalMessage", decision.getMessage(), "approvalReason", decision.getReason(),
                "approvalMetadata", decision.getMetadata()));
    }

    /**
     * 发布结构化用户输入请求。
     *
     * @param turn 等待输入的 Turn
     * @param call 触发表单的工具调用
     * @param form 需要展示的表单定义
     */
    void notifyToolInputRequested(AgentTurn turn, ToolCall call,
                                  AgentFormDefinition form) {
        publish(turn, AgentEventType.TOOL_INPUT_REQUESTED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "formKey", form.getFormKey()));
    }

    /**
     * 发布外部工具执行请求。Snapshot 已经保存，业务监听器可以安全派发给外部执行器。
     */
    void notifyExternalToolRequested(AgentTurn turn, ToolCall call, Tool tool) {
        publish(turn, AgentEventType.EXTERNAL_TOOL_REQUESTED,
            attributes("toolCallId", callKey(call), "toolName", call.getName(),
                "arguments", call.getArguments(), "toolMetadata", tool.getMetadata()));
    }

    /**
     * 发布外部工具结果已经写入原 ToolCall 的事实事件。
     */
    void notifyExternalToolResult(AgentTurn turn, AgentSuspension suspension,
                                  AgentResumeCommand command) {
        AgentEventType type = command.getType() == AgentResumeCommandType.TOOL_ERROR
            ? AgentEventType.EXTERNAL_TOOL_FAILED
            : AgentEventType.EXTERNAL_TOOL_COMPLETED;
        publish(turn, type,
            attributes("toolCallId", suspension.getCorrelationId(),
                "toolName", suspension.getMetadata().get("toolName")));
    }

    /**
     * 发布自动重试已调度事件，包含次数、下次运行时间和失败原因。
     *
     * @param turn  已进入重试等待的 Turn
     * @param error 本次失败异常
     */
    void notifyRetryScheduled(AgentTurn turn, Throwable error) {
        publish(turn, AgentEventType.RETRY_SCHEDULED,
            attributes("retryCount", turn.getRetryCount(),
                "nextRunnableAt", turn.getNextRunnableAt(), "error", errorMessage(error)));
    }

    /**
     * 发布运行预算超限事件。
     *
     * @param turn   因预算结束的 Turn
     * @param reason 稳定的超限维度和用量说明
     */
    void notifyBudgetExceeded(AgentTurn turn, String reason) {
        publish(turn, AgentEventType.BUDGET_EXCEEDED, attributes("reason", reason));
    }

    /**
     * 创建不可变事件并同步通知全部监听器；空 Turn 不产生事件。
     */
    void publish(AgentTurn turn, AgentEventType type, Map<String, ?> data) {
        if (turn == null) return;
        Map<String, Object> values = attributes(
            "status", turn.getStatus(),
            "executionPoint", turn.getExecutionPoint(),
            "stepCount", turn.getStepCount(),
            "maxSteps", turn.getExecutionPolicy().getMaxSteps());
        if (data != null) values.putAll(data);
        long sequence = sequences.computeIfAbsent(turn.getId(), key -> new AtomicLong())
            .incrementAndGet();
        AgentEvent event = new AgentEvent(turn.getId(), turn.getAgent().getId(),
            turn.getAgent().getVersion(),
            sequence, type, values);
        for (AgentEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException error) {
                log.warn("Agent event listener failed", error);
            }
        }
    }

    /**
     * 生成当前模型迭代次数、上限和剩余额度属性。
     *
     * @param turn 当前 Turn
     * @return 有序事件属性
     */
    private Map<String, Object> iterationAttributes(AgentTurn turn) {
        int maxIterations = turn.getExecutionPolicy().getMaxIterations();
        return attributes("iteration", turn.getIterationCount(),
            "maxIterations", maxIterations,
            "remainingIterations", Math.max(0, maxIterations - turn.getIterationCount()));
    }

    private Map<String, Object> compressionAttributes(
        AgentContextCompressionResult result) {
        Map<String, Object> values = attributes("compressed", result != null && result.isCompressed());
        if (result == null || result.getState() == null) return values;
        AgentContextCompressionState state = result.getState();
        values.putAll(attributes("coveredUntilMessageId", state.getCoveredUntilMessageId(),
            "stateVersion", state.getVersion(),
            "modelMessageCount", result.getModelMessages().size()));
        return values;
    }

    /**
     * 将键值序列转换为有序 Map，忽略空键、空值及末尾孤立参数。
     *
     * @param values 交替排列的键和值
     * @return 可继续合并的属性 Map
     */
    private Map<String, Object> attributes(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index] != null && values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return result;
    }

    /**
     * 获取事件使用的稳定工具调用键，ID 缺失时使用工具名。
     *
     * @param call 工具调用
     * @return 事件关联键
     */
    private String callKey(ToolCall call) {
        return StringUtil.hasText(call.getId()) ? call.getId() : call.getName();
    }

    /**
     * 将异常转换为同时包含类型和消息的可观测文本。
     *
     * @param error 可选异常
     * @return 格式化文本；无异常时返回 {@code null}
     */
    private String errorMessage(Throwable error) {
        return error == null ? null : error.getClass().getName() + ": " + error.getMessage();
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.tool.ToolErrorStrategy;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

/**
 * Agent 运行生命周期监听器。
 *
 * <p>监听器用于接入日志、UI 状态更新、审计和自定义指标，不参与 Agent 的控制决策。
 * 所有方法均提供空实现，调用方只需覆盖关注的事件。监听器抛出的 RuntimeException 会被
 * {@link AgentRunner} 捕获并记录，不会改变主运行流程。</p>
 *
 * <p>事件顺序大致为：</p>
 * <pre>
 * onRunStart
 *   onModelStart -> onModelEnd
 *   [onToolStart -> onToolEnd/onToolError]...
 *   ...可能重复多个模型回合...
 * onRunComplete/onRunFailed/onRunCancelled/onMaxIterationsReached
 * </pre>
 */
public interface AgentListener {

    /**
     * AgentRun 第一次被 Runner 推进时触发，只触发一次。
     *
     * @param run 当前运行状态
     */
    default void onRunStart(AgentRun run) {
    }

    /**
     * 每次调用 ChatModel 之前触发。
     *
     * @param run 当前运行状态；此时迭代次数已经增加
     */
    default void onModelStart(AgentRun run) {
    }

    /**
     * ChatModel 返回且响应通过基础校验后触发。
     *
     * @param run      当前运行状态
     * @param response 模型原始响应
     */
    default void onModelEnd(AgentRun run, AiMessageResponse response) {
    }

    /**
     * 每个工具调用开始执行前触发。
     *
     * @param run      当前运行状态
     * @param toolCall 模型返回的原始工具调用
     */
    default void onToolStart(AgentRun run, ToolCall toolCall) {
    }

    /**
     * 工具调用已经转换为 ToolMessage 后触发。
     *
     * <p>当错误策略为 {@link ToolErrorStrategy#RETURN_ERROR_TO_MODEL} 时，工具失败后也会触发
     * 本事件，此时 result 中保存的是结构化错误。</p>
     *
     * @param run      当前运行状态
     * @param toolCall 对应的原始工具调用
     * @param result   即将写入消息历史的工具结果
     */
    default void onToolEnd(AgentRun run, ToolCall toolCall, ToolMessage result) {
    }

    /**
     * 找不到工具或工具执行抛出异常时触发。
     *
     * @param run      当前运行状态
     * @param toolCall 执行失败的工具调用
     * @param error    原始异常
     */
    default void onToolError(AgentRun run, ToolCall toolCall, Throwable error) {
    }

    /**
     * 模型返回最终消息、运行正常完成时触发。
     */
    default void onRunComplete(AgentRun run) {
    }

    /**
     * 运行因不可恢复异常失败时触发。
     */
    default void onRunFailed(AgentRun run, Throwable error) {
    }

    /**
     * Runner 检测到取消请求并终止运行时触发。
     */
    default void onRunCancelled(AgentRun run) {
    }

    /**
     * 模型调用次数达到执行策略上限时触发。
     */
    default void onMaxIterationsReached(AgentRun run) {
    }

    /** 运行模式达到总 step 上限时触发。 */
    default void onMaxStepsReached(AgentRun run) {
    }

    /**
     * 一个稳定执行边界已经成功保存到 AgentRunStore。
     */
    default void onCheckpoint(AgentRun run, AgentRunSnapshot snapshot) {
    }

    /**
     * AgentRun 进入等待外部事件的阻塞状态。
     */
    default void onRunSuspended(AgentRun run, AgentSuspension suspension) {
    }

    /**
     * 外部命令已被接受，AgentRun 即将继续推进。
     */
    default void onRunResumed(AgentRun run, AgentResumeCommand command) {
    }

    /**
     * 工具调用已经进入等待审批状态。
     */
    default void onToolApprovalRequested(AgentRun run, ToolCall toolCall) {
    }

    /**
     * 可恢复异常已经安排下一次执行时间。
     */
    default void onRetryScheduled(AgentRun run, Throwable error) {
    }

    /**
     * Run 因资源预算耗尽而终止。
     */
    default void onBudgetExceeded(AgentRun run, String reason) {
    }

    /**
     * 父 Run 已创建子 Run 并进入等待状态。
     */
    default void onChildStarted(AgentRun parent, AgentRun child) {
    }
}

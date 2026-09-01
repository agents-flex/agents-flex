/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * AgentTurn 生命周期状态。
 *
 * <p>状态转换通常由 {@link AgentRunner} 负责，调用方只需要读取状态和发起取消请求。</p>
 */
public enum AgentTurnStatus {

    /**
     * 已创建但尚未执行第一个模型回合。
     */
    READY,

    /**
     * 正在执行，或已完成工具调用并等待下一次模型回合。
     */
    RUNNING,

    /**
     * 缺少必要信息，任务已持久化并等待用户补充输入。
     */
    WAITING_FOR_USER,

    /**
     * 高风险工具尚未执行，任务正在等待外部审批。
     */
    WAITING_FOR_APPROVAL,

    /**
     * ToolCall 已派发给外部执行器，正在等待工具结果。
     */
    WAITING_FOR_TOOL,

    /**
     * 可恢复错误已安排后续重试，等待调度时间到达。
     */
    RETRY_SCHEDULED,

    /**
     * 模型已经返回不含 ToolCall 的最终消息。
     */
    COMPLETED,

    /**
     * 模型调用、工具调用或协议校验发生不可恢复错误。
     */
    FAILED,

    /**
     * 调用方请求取消，Runner 已停止继续推进。
     */
    CANCELLED,

    /**
     * 达到最大模型迭代次数，仍未得到最终回答。
     */
    MAX_ITERATIONS_REACHED,

    /**
     * Runner 推进次数达到总 step 上限。
     */
    MAX_STEPS_REACHED,

    /**
     * 时间、Token 或工具调用次数超过执行预算。
     */
    BUDGET_EXCEEDED;

    /**
     * 判断当前状态是否暂停并等待外部事件。
     */
    public boolean isBlocked() {
        return this == WAITING_FOR_USER
            || this == WAITING_FOR_APPROVAL
            || this == WAITING_FOR_TOOL
            || this == RETRY_SCHEDULED;
    }

    /**
     * 判断当前状态是否已经结束，结束后的 Turn 不能再次调用 {@link AgentRunner#step(AgentTurn)}。
     */
    public boolean isTerminal() {
        return this == COMPLETED
            || this == FAILED
            || this == CANCELLED
            || this == MAX_ITERATIONS_REACHED
            || this == MAX_STEPS_REACHED
            || this == BUDGET_EXCEEDED;
    }
}

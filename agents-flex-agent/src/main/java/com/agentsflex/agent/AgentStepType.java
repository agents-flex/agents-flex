/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 单次 {@link AgentRunner#step(AgentRun)} 的执行结果类型。
 */
public enum AgentStepType {

    /**
     * 自定义运行模式已经保存中间状态，可以继续推进。
     */
    PROGRESSED,

    /**
     * 模型返回了一个或多个 ToolCall，Runner 已完成执行并写入 ToolMessage。
     */
    TOOLS_EXECUTED,

    /**
     * 模型返回最终消息，本次 AgentRun 已正常完成。
     */
    COMPLETED,

    /**
     * 当前步骤发生不可恢复异常，AgentRun 已失败。
     */
    FAILED,

    /**
     * 当前步骤检测到取消请求，AgentRun 已取消。
     */
    CANCELLED,

    /**
     * AgentRun 已进入等待外部输入、审批、子任务或重试调度的阻塞状态。
     */
    BLOCKED,

    /**
     * 当前步骤开始前发现模型调用次数已达到上限。
     */
    MAX_ITERATIONS_REACHED,

    /**
     * 运行模式达到总 step 上限。
     */
    MAX_STEPS_REACHED,

    /**
     * 执行预算已经耗尽。
     */
    BUDGET_EXCEEDED
}

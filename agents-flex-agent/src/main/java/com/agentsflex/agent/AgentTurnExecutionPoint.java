/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * AgentTurn 在模型与工具闭环中的固定执行入口。
 *
 * <p>该枚举描述 Agent 运行时下一步应从哪里继续执行，不是 Turn 的生命周期状态，
 * 也不承担工作流编排职责。它随 Snapshot 持久化，使挂起、重试或跨进程恢复后能够从
 * 确定的运行边界继续推进。</p>
 */
public enum AgentTurnExecutionPoint {

    /**
     * 调用模型，让模型生成最终回复或 ToolCall。
     */
    INVOKE_MODEL,

    /**
     * 处理模型已经生成但尚未完成的 ToolCall。
     */
    PROCESS_TOOLS,

    /**
     * 运行已经进入终止状态，不再调用模型或处理工具。
     */
    FINISHED
}

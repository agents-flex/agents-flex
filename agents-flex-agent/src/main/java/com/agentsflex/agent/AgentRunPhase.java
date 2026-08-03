/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * AgentRun 在模型与工具闭环中的固定执行阶段。
 *
 * <p>该枚举只描述 Agent 运行时自身的执行位置，不承担工作流编排职责。使用枚举而不是任意字符串，
 * 可以避免 Snapshot 中出现运行时无法识别的跳转目标。</p>
 */
public enum AgentRunPhase {

    /**
     * 调用模型，让模型生成最终回复或 ToolCall。
     */
    MODEL,

    /**
     * 执行模型已经生成但尚未处理完成的 ToolCall。
     */
    TOOLS,

    /**
     * 运行已经进入终止状态，不再执行模型或工具。
     */
    FINISHED
}

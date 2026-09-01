/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 同一模型响应中的多个本地 ToolCall 的执行方式。
 */
public enum AgentToolExecutionMode {
    /**
     * 按模型返回顺序逐个执行，默认模式。
     */
    SEQUENTIAL,
    /**
     * 在独立任务中并行执行，结果仍按 ToolCall 原始顺序写回上下文。
     * 使用方应保证并行工具彼此独立，且在超时或失败后具备业务幂等性。
     */
    PARALLEL
}

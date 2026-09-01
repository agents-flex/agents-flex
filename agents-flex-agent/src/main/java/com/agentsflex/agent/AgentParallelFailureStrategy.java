/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 并行 ToolCall 批次出现失败时的处理方式。
 */
public enum AgentParallelFailureStrategy {
    /**
     * 立即将失败交给 Agent 的统一重试/失败流程；已经成功的调用仍会先保存。
     */
    FAIL_FAST,
    /**
     * 将每个失败编码为 ToolMessage，交回模型决定后续动作。
     */
    RETURN_ERRORS_TO_MODEL
}

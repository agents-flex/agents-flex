/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.compression;

/**
 * 即时上下文压缩失败后的处理方式。
 */
public enum AgentCompressionFailureStrategy {
    /**
     * 保持严格语义，终止当前模型调用，并将压缩异常交给 Agent 重试/失败策略。
     */
    FAIL,
    /**
     * 忽略本次压缩异常或非法摘要，继续使用未压缩的完整历史。
     */
    USE_ORIGINAL
}

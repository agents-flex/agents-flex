/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 工具结果超过上下文字符上限时的处理方式。
 *
 * <p>{@link #FAIL} 保持严格语义，防止模型看到不完整结果；{@link #TRUNCATE} 允许继续运行，
 * 但会在结果末尾加入明确的截断标记。默认使用严格失败，避免现有应用在升级后静默丢失工具结果。</p>
 */
public enum AgentToolResultOverflowStrategy {
    /**
     * 超限即失败，由 Agent 的工具错误/重试策略继续处理。
     */
    FAIL,
    /**
     * 保留不超过上限的文本，并标记结果已经被截断。
     */
    TRUNCATE
}

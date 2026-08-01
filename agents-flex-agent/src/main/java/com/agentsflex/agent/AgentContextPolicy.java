/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.prompt.MemoryPrompt;

/**
 * 配置每次模型调用可见上下文的策略。
 *
 * <p>AgentRun 仍会在 Checkpoint 中保存完整消息历史。本策略只调整发送给模型的消息窗口、文本截断等
 * 参数，因此平台可以实现最近消息、Token 窗口、摘要记忆或自定义上下文装配，而不会破坏恢复数据。</p>
 */
@FunctionalInterface
public interface AgentContextPolicy {

    /** 将上下文读取策略应用到当前 Run 使用的 Prompt。 */
    void configure(MemoryPrompt prompt);

    /** 保留 MemoryPrompt 默认的消息窗口和截断行为。 */
    static AgentContextPolicy defaults() {
        return prompt -> { };
    }

    /** 每次模型调用附加当前 Memory 中的全部消息。 */
    static AgentContextPolicy fullHistory() {
        return prompt -> prompt.setMaxAttachedMessageCount(Integer.MAX_VALUE);
    }

    /**
     * 每次模型调用只附加最近指定数量的消息。
     *
     * <p>数量按消息计算，不按自然语言对话轮次计算。包含 ToolCall 的场景应为协议消息预留足够空间。</p>
     */
    static AgentContextPolicy recentMessages(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than 0");
        }
        return prompt -> prompt.setMaxAttachedMessageCount(maxMessages);
    }
}

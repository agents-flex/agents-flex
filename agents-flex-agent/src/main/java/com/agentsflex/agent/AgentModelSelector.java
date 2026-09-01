/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.prompt.Prompt;

/**
 * 为当前 Turn 选择实际使用的 ChatModel。
 */
@FunctionalInterface
public interface AgentModelSelector {
    /**
     * 为当前 Turn 选择实际调用的模型。
     *
     * <p>选择发生在上下文窗口构建之后，因此可以依据当前 Turn、最终 Prompt、文本模型和
     * 多模态模型进行路由。返回 {@code null} 会被 Runner 当作配置错误处理。</p>
     *
     * @param turn            当前执行中的 Turn
     * @param prompt          本次模型请求实际使用的 Prompt
     * @param textModel       Agent 配置的默认文本模型
     * @param multimodalModel Agent 配置的可选多模态模型
     * @return 要调用的模型，不得为 {@code null}
     */
    ChatModel select(AgentTurn turn, Prompt prompt, ChatModel textModel, ChatModel multimodalModel);
}

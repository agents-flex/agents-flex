/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.model.chat.tool.Tool;

/**
 * 判断工具是否应出现在当前 Turn 的模型 Prompt 中。
 */
@FunctionalInterface
public interface AgentToolVisibilityPolicy {
    /**
     * 判断指定工具是否加入本次请求提供给模型的工具定义。
     *
     * <p>该策略只控制“模型能看到什么”，不改变 Runner 的完整可执行工具索引。因而模型若
     * 通过已持久化的 ToolCall 恢复执行，仍会按 Agent 版本解析原工具。</p>
     *
     * @param turn 当前 Turn
     * @param tool 待判断的工具
     * @return {@code true} 表示加入 Prompt，{@code false} 表示隐藏
     */
    boolean isVisible(AgentTurn turn, Tool tool);

    static AgentToolVisibilityPolicy all() {
        return (turn, tool) -> true;
    }
}

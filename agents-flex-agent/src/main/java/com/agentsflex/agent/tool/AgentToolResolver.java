/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.core.model.chat.tool.Tool;

/**
 * 为 AgentRunner 按名称解析当前 Turn 可以执行的动态 Tool。
 *
 * <p>Resolver 只负责提供本地可执行对象，不负责把 Tool 定义加入模型 Prompt。Tool 的模型可见性
 * 应由提供该 Resolver 的 Middleware 在模型调用前处理。传入当前 Turn 是为了让实现能够根据
 * 已持久化的租户、权限或搜索激活结果拒绝未授权的动态工具。</p>
 */
@FunctionalInterface
public interface AgentToolResolver {

    /**
     * @param turn     当前执行轮次
     * @param toolName 模型 ToolCall 中的工具名称
     * @return 当前 Turn 可执行的 Tool；不支持或不允许时返回 {@code null}
     */
    Tool resolve(AgentTurn turn, String toolName);
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent.tool;

import com.agentsflex.core.model.chat.tool.Tool;

/**
 * 负责创建工具持久化引用，并在运行进程中重新绑定可执行 Tool。
 *
 * <p>{@link #register(String, String, Tool)} 在工具进入运行时目录时返回稳定引用。Runner 将该引用与
 * pending ToolCall 一起写入 Snapshot，恢复时只通过 {@link #resolve(AgentToolReference)} 定位实现。</p>
 */
public interface AgentToolRegistry {

    /**
     * 注册工具并返回可持久化引用。
     *
     * <p>同一 Agent 版本中的同一工具必须返回语义稳定的引用。MCP 或远程目录实现可以在引用中写入
     * bindingId、bindingVersion 和重新绑定所需的非敏感 metadata。</p>
     */
    AgentToolReference register(String agentId, String agentVersion, Tool tool);

    /**
     * 根据持久化引用解析当前进程中的可执行工具。
     *
     * @return 可执行工具；当前进程无法解析该引用时返回 {@code null}
     */
    Tool resolve(AgentToolReference reference);
}

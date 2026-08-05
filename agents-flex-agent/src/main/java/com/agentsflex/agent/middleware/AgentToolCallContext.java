/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.middleware;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;

/**
 * 工具中间件访问已解析工具定义和模型 ToolCall 的上下文。
 *
 * <p>Tool 已经通过当前 Agent 的工具索引解析，ToolCall 保留模型参数和稳定调用 ID。Middleware
 * 可以校验或转换调用，但应避免修改会破坏 Snapshot 恢复一致性的关联 ID。</p>
 */
public final class AgentToolCallContext extends AgentMiddlewareContext {
    /** 当前 Agent 中解析出的可执行工具。 */
    private final Tool tool;
    /** 模型生成并等待执行的工具调用。 */
    private final ToolCall toolCall;

    public AgentToolCallContext(AgentRunner runner, AgentTurn turn, Tool tool, ToolCall toolCall) {
        super(runner, turn, turn.getPrompt());
        this.tool = tool;
        this.toolCall = toolCall;
    }

    /** @return 已解析的可执行工具 */
    public Tool getTool() { return tool; }
    /** @return 当前模型 ToolCall */
    public ToolCall getToolCall() { return toolCall; }
}

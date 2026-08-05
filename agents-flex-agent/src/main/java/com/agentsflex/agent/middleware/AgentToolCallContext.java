/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.middleware;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;

/**
 * 工具中间件访问已解析工具定义和模型 ToolCall 的上下文。
 *
 * <p>Tool 已经通过当前 Agent 的工具索引解析，ToolCall 保留模型参数和稳定调用 ID。组合的
 * AgentToolContext 可安全传递给实际 Tool；Runner、Turn 和 Prompt 仍只供 Middleware 控制执行链。</p>
 */
public final class AgentToolCallContext extends AgentMiddlewareContext {
    /** 可传递给 Tool 的受控只读上下文。 */
    private final AgentToolContext toolContext;

    public AgentToolCallContext(AgentRunner runner, AgentTurn turn,
                                AgentToolContext toolContext) {
        super(runner, turn, turn.getPrompt());
        if (toolContext == null) throw new IllegalArgumentException("toolContext must not be null");
        this.toolContext = toolContext;
    }

    /** @return 已解析的可执行工具 */
    public Tool getTool() { return toolContext.getTool(); }
    /** @return 当前模型 ToolCall */
    public ToolCall getToolCall() { return toolContext.getToolCall(); }
    /** @return 可供 Middleware 和实际 Tool 共用的受控上下文 */
    public AgentToolContext getToolContext() { return toolContext; }
}

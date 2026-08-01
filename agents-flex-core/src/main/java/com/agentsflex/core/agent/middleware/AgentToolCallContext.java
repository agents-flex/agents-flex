/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent.middleware;

import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.agent.AgentRunner;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;

/** 工具中间件访问工具定义和 ToolCall 的上下文。 */
public final class AgentToolCallContext extends AgentMiddlewareContext {
    private final Tool tool;
    private final ToolCall toolCall;

    public AgentToolCallContext(AgentRunner runner, AgentRun run, Tool tool, ToolCall toolCall) {
        super(runner, run, run.getPrompt());
        this.tool = tool;
        this.toolCall = toolCall;
    }

    public Tool getTool() { return tool; }
    public ToolCall getToolCall() { return toolCall; }
}

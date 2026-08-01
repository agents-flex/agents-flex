/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent.tool;

import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;

/**
 * 在工具产生副作用前判断是否允许执行、要求审批或直接拒绝。
 */
@FunctionalInterface
public interface ToolApprovalPolicy {

    ToolApprovalDecision decide(AgentRun run, ToolCall toolCall, Tool tool);

    static ToolApprovalPolicy allowAll() {
        return (run, call, tool) -> ToolApprovalDecision.ALLOW;
    }
}

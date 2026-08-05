/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.tool;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;

/**
 * 在工具产生副作用前判断是否允许执行、要求审批或直接拒绝。
 */
@FunctionalInterface
public interface ToolApprovalPolicy {

    /**
     * 在实际工具执行前计算审批决定。
     *
     * @param turn     当前 Turn，只应用于读取执行状态和业务上下文
     * @param toolCall 模型生成的原始工具调用
     * @param tool     已从当前 Agent 工具集合解析出的工具定义
     * @return 允许执行、要求外部审批或直接拒绝的非空决定
     */
    ToolApprovalDecision decide(AgentTurn turn, ToolCall toolCall, Tool tool);

    /**
     * @return 对所有工具调用直接放行的无状态策略
     */
    static ToolApprovalPolicy allowAll() {
        return (turn, call, tool) -> ToolApprovalDecision.ALLOW;
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.exception;

import com.agentsflex.agent.tool.ToolApprovalDecision;

/**
 * Tool 主动请求暂停 AgentTurn 的统一控制流异常。
 *
 * <p>Runner 会沿异常 cause 链识别本类型，因此 Middleware 或 Interceptor 可以补充上下文后重新包装
 * 异常，而不会把审批或表单请求误当成普通工具失败。普通本地 Tool 可以直接构造本类主动申请审批，
 * 无需提前声明额外 Tool 元数据。该类型不表示故障，业务代码不应吞掉它。</p>
 */
public class AgentToolSuspensionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ToolApprovalDecision decision;

    /**
     * 普通本地 Tool 主动申请审批的统一入口。
     *
     * @param decision 必须为 REQUIRE_APPROVAL，内容将进入持久化 Suspension
     */
    public AgentToolSuspensionException(ToolApprovalDecision decision) {
        super(message(decision));
        if (decision == null
            || decision.getOutcome() != ToolApprovalDecision.Outcome.REQUIRE_APPROVAL) {
            throw new IllegalArgumentException(
                "decision outcome must be REQUIRE_APPROVAL");
        }
        this.decision = decision;
    }

    /**
     * 供表单等其他暂停子类型保存自己的消息，不把它解释为审批请求。
     */
    protected AgentToolSuspensionException(String message) {
        super(message);
        this.decision = null;
    }

    /**
     * @return Tool 主动申请的审批决定；表单等非审批子类型返回 null
     */
    public ToolApprovalDecision getDecision() {
        return decision;
    }

    private static String message(ToolApprovalDecision decision) {
        return decision == null || decision.getMessage() == null
            ? "Tool requires approval" : decision.getMessage();
    }
}

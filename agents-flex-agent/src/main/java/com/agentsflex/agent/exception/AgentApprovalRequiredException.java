/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.exception;

import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.ToolApprovalDecision;

/**
 * 本地业务工具在完成只读预检后，主动请求人工审批的控制流异常。
 *
 * <p>该能力是 {@code toolApprovalPolicy} 的补充，而不是替代品。Runner 始终先执行 Agent 级审批策略；
 * 只有策略明确返回 {@link ToolApprovalDecision.Outcome#ALLOW}，本地工具才会开始执行并有机会抛出本异常。
 * 因此，工具内部的运行时判断只能增加审批，不能绕过中央策略的等待审批或直接拒绝。</p>
 *
 * <p>Runner 捕获本异常后会保留当前 ToolCall，并使用既有的 TOOL_APPROVAL 挂起、事件、超时、恢复和
 * 审计协议。审批通过后 Java 调用栈不会从抛出点继续，而是从头再次执行整个工具函数。工具应重新
 * 构造当前预检决定，并通过 {@link AgentToolContext#isToolApproved(ToolApprovalDecision)}
 * 校验已有批准是否仍绑定同一业务快照，否则应再次申请审批。</p>
 *
 * <p>本异常必须在写数据库、扣费、发送消息或调用外部写入接口之前抛出。允许在抛出前执行确定审批
 * 内容所必需的只读查询；这些查询也应可重复执行。工具仍应使用
 * {@link AgentToolContext#getIdempotencyKey()} 为最终副作用提供业务幂等保护。</p>
 */
public final class AgentApprovalRequiredException extends AgentToolSuspensionException {

    private static final long serialVersionUID = 1L;

    private final ToolApprovalDecision decision;

    /**
     * 创建 Tool 主动审批请求。
     *
     * @param decision 必须是 REQUIRE_APPROVAL 类型；其中的 code、message、reason 和 metadata 会进入
     *                 可持久化审批挂起点
     */
    public AgentApprovalRequiredException(ToolApprovalDecision decision) {
        super(message(decision));
        if (decision == null
            || decision.getOutcome() != ToolApprovalDecision.Outcome.REQUIRE_APPROVAL) {
            throw new IllegalArgumentException(
                "decision outcome must be REQUIRE_APPROVAL");
        }
        this.decision = decision;
    }

    /**
     * 返回本次 Tool 主动申请的审批决定。
     *
     * <p>该访问器只存在于审批异常上，调用方无需再通过 null 判断区分审批与表单暂停。</p>
     *
     * @return 非空且 outcome 为 REQUIRE_APPROVAL 的审批决定
     */
    public ToolApprovalDecision getDecision() {
        return decision;
    }

    private static String message(ToolApprovalDecision decision) {
        return decision == null || decision.getMessage() == null
            ? "Tool requires approval" : decision.getMessage();
    }
}

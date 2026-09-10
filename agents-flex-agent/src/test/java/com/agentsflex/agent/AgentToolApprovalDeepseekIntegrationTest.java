/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.exception.AgentToolSuspensionException;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.agent.tool.ToolApprovalRecord;
import com.agentsflex.agent.tool.ToolApprovalStage;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.model.chat.deepseek.DeepseekChatModel;
import com.agentsflex.model.chat.deepseek.DeepseekConfig;
import org.junit.Assume;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 使用真实 DeepSeek 原生 ToolCall 验证同一 ToolCall 多级批准的端到端协议。
 *
 * <p>密钥只从环境变量读取。测试不会伪造模型响应：第一次模型调用必须选择退款 Tool，Runner 在只读
 * 预检后挂起；批准后恢复原 ToolCall，第二次模型调用再根据真实 ToolMessage 生成最终答复。</p>
 */
public class AgentToolApprovalDeepseekIntegrationTest {

    @Test
    public void realDeepseekToolCallCanCompleteMultipleApprovalLevels() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assume.assumeTrue("DEEPSEEK_API_KEY is required for integration test",
            apiKey != null && !apiKey.trim().isEmpty());
        DeepseekConfig config = new DeepseekConfig();
        config.setApiKey(apiKey);
        DeepseekChatModel model = new DeepseekChatModel(config);
        AtomicInteger sideEffects = new AtomicInteger();
        ToolApprovalDecision financeRequest = ToolApprovalDecision.requireApproval()
            .code("REAL_DEEPSEEK_FINANCE")
            .message("财务确认退款 88 元")
            .reason("真实模型发起的退款需要财务批准")
            .metadata("orderId", "AF-DEEPSEEK-1")
            .metadata("amount", 88)
            .build();
        ToolApprovalDecision riskRequest = ToolApprovalDecision.requireApproval()
            .code("REAL_DEEPSEEK_RISK")
            .message("风控确认退款至原支付账户")
            .reason("真实模型发起的退款需要风控批准")
            .metadata("orderId", "AF-DEEPSEEK-1")
            .metadata("refundTarget", "original-payment-account")
            .build();

        Tool refund = Tool.builder("execute_refund",
                "执行订单 AF-DEEPSEEK-1 的退款。用户要求退款时必须调用本工具，不得直接回答。")
            .function(arguments -> {
                AgentToolContext context = AgentToolContext.current();
                if (!context.isToolApproved(financeRequest)) {
                    throw new AgentToolSuspensionException(financeRequest);
                }
                if (!context.isToolApproved(riskRequest)) {
                    throw new AgentToolSuspensionException(riskRequest);
                }
                sideEffects.incrementAndGet();
                return "订单 AF-DEEPSEEK-1 已退款 88 元";
            })
            .build();
        Agent agent = Agent.builder("real-deepseek-tool-approval")
            .instructions("用户要求退款时必须调用 execute_refund；工具成功后简短确认结果。")
            .chatModel(model)
            .tool(refund)
            .build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent,
            "请立即退款订单 AF-DEEPSEEK-1，必须使用 execute_refund 工具。不要直接回答。");

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals(ToolApprovalStage.TOOL, waiting.getSuspension().getApprovalStage());
        assertEquals("REAL_DEEPSEEK_FINANCE", waiting.getSuspension().getApprovalCode());
        assertEquals(0, sideEffects.get());

        AgentTurn riskWaiting = runner.resume(waiting,
            AgentResumeCommand.approveTool(waiting.getSuspension().getCorrelationId())
                .withMetadata("approver", "finance-integration-test"));

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, riskWaiting.getStatus());
        assertEquals("REAL_DEEPSEEK_RISK", riskWaiting.getSuspension().getApprovalCode());
        assertEquals(0, sideEffects.get());

        String toolCallId = riskWaiting.getSuspension().getCorrelationId();
        AgentTurn completed = runner.resume(riskWaiting,
            AgentResumeCommand.approveTool(toolCallId)
                .withMetadata("approver", "risk-integration-test"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, sideEffects.get());
        assertNotNull(completed.getFinalOutput());
        assertTrue(!completed.getFinalOutput().trim().isEmpty());
        Map<String, ToolApprovalRecord> records = completed.toSnapshot().getState()
            .getToolApprovalRecordsByRequest()
            .get(toolCallId);
        assertEquals(2, records.size());
        assertTrue(records.get(financeRequest.getRequestFingerprint()).isApproved());
        assertTrue(records.get(riskRequest.getRequestFingerprint()).isApproved());
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.exception.AgentApprovalRequiredException;
import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.agent.exception.AgentToolSuspensionException;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentToolCallChain;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.AgentFormDefinition;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.AgentToolResumeType;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.agent.tool.ToolApprovalRecord;
import com.agentsflex.agent.tool.ToolApprovalStage;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;
import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Agent 级中央审批策略与 Tool 主动审批两类入口的安全边界集成测试。
 *
 * <p>这些测试刻意把“只读预检”和“最终副作用”分开计数，确保 Tool 主动审批只会发生在副作用之前，
 * 并验证恢复后重新执行 Tool 时不会再次申请相同审批。</p>
 */
public class AgentToolApprovalIntegrationTest {

    @Test
    public void localToolCanRequestApprovalAfterReadOnlyPreflight() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("refund-call", "refund", "{\"orderId\":\"O-1001\"}")));
        model.enqueue(prompt -> new AiMessage("refund completed"));

        AtomicInteger readOnlyPreflights = new AtomicInteger();
        AtomicInteger sideEffects = new AtomicInteger();
        List<AgentToolContext> contexts = new ArrayList<>();
        ToolApprovalDecision request = ToolApprovalDecision.requireApproval()
            .code("LARGE_REFUND")
            .message("是否允许执行大额退款？")
            .reason("只读预检发现退款金额超过自动处理额度")
            .metadata("orderId", "O-1001")
            .metadata("amount", 20000)
            .build();
        Tool refund = approvalRequestingTool("refund", arguments -> {
            AgentToolContext context = AgentToolContext.current();
            contexts.add(context);

            // 实际项目可以在这里查询退款金额、客户等级等只读信息，再据此生成审批展示内容。
            readOnlyPreflights.incrementAndGet();
            if (!context.isToolApproved(request)) {
                throw new AgentApprovalRequiredException(request);
            }

            // 只有与当前 ToolCall 匹配的人工批准已写入 Snapshot 后，才允许触发最终副作用。
            sideEffects.incrementAndGet();
            return "ok";
        });
        Agent agent = Agent.builder("tool-approval-agent")
            .chatModel(model)
            .tool(refund)
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner firstRunner = new AgentRunner(store, new InMemoryAgentLoader(agent));

        AgentTurn waiting = firstRunner.run(agent, "refund order");

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals(AgentSuspensionType.TOOL_APPROVAL, waiting.getSuspension().getType());
        assertEquals("refund-call", waiting.getSuspension().getCorrelationId());
        assertEquals(ToolApprovalStage.TOOL, waiting.getSuspension().getApprovalStage());
        assertEquals("LARGE_REFUND", waiting.getSuspension().getApprovalCode());
        assertEquals("O-1001", waiting.getSuspension().getMetadata().get("orderId"));
        assertEquals("TOOL",
            waiting.getSuspension().getMetadata().get("agentsflex.approvalTrigger"));
        assertEquals(1, readOnlyPreflights.get());
        assertEquals(0, sideEffects.get());
        // Tool 主动审批前虽然进入过 Tool，但没有完成业务调用，所以不占用 maxToolCalls 预算。
        assertEquals(0, waiting.getToolCallCount());

        // 使用新的 Runner 恢复，证明审批结论、原始 ToolCall 和审批元数据都能跨进程式边界持久化。
        AgentRunner secondRunner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentTurn completed = secondRunner.resume(waiting.getId(),
            AgentResumeCommand.approveTool("refund-call")
                .withMetadata("approverId", "manager-7"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(2, readOnlyPreflights.get());
        assertEquals(1, sideEffects.get());
        assertEquals(2, contexts.size());
        assertFalse(contexts.get(0).isToolApproved(request));
        AgentToolContext approved = contexts.get(1);
        assertTrue(approved.isToolApproved(request));
        assertTrue(approved.isApprovalResumed());
        assertTrue(approved.isReplay());
        assertEquals(2, approved.getExecutionAttempt());
        assertEquals("manager-7", approved.getResumeInfo().getMetadata().get("approverId"));
        assertEquals("TOOL",
            approved.getResumeInfo().getMetadata().get("agentsflex.approvalTrigger"));
    }

    @Test
    public void sameToolCallCanAccumulateMultipleApprovalsAcrossStoreResumes() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("multi-call", "release_payment", "{}")));
        model.enqueue(prompt -> new AiMessage("payment released"));
        ToolApprovalDecision financeRequest = ToolApprovalDecision.requireApproval()
            .code("FINANCE_APPROVAL")
            .message("财务是否批准本次付款？")
            .metadata("amount", 50000)
            .build();
        ToolApprovalDecision complianceRequest = ToolApprovalDecision.requireApproval()
            .code("COMPLIANCE_APPROVAL")
            .message("合规是否批准向该收款方付款？")
            .metadata("payee", "supplier-8")
            .build();
        List<AgentToolContext> contexts = new ArrayList<>();
        AtomicInteger sideEffects = new AtomicInteger();
        Tool tool = approvalRequestingTool("release_payment", arguments -> {
            AgentToolContext context = AgentToolContext.current();
            contexts.add(context);
            // 每次恢复都从函数开头执行。第一级批准必须在第二级批准后仍可被准确识别。
            if (!context.isToolApproved(financeRequest)) {
                throw new AgentApprovalRequiredException(financeRequest);
            }
            if (!context.isToolApproved(complianceRequest)) {
                throw new AgentApprovalRequiredException(complianceRequest);
            }
            sideEffects.incrementAndGet();
            return "released";
        });
        Agent agent = Agent.builder("multi-level-tool-approval")
            .chatModel(model).tool(tool).build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();

        AgentTurn financeWaiting = new AgentRunner(
            store, new InMemoryAgentLoader(agent)).run(agent, "release payment");
        assertEquals("FINANCE_APPROVAL", financeWaiting.getSuspension().getApprovalCode());
        assertEquals(0, sideEffects.get());

        AgentTurn complianceWaiting = new AgentRunner(
            store, new InMemoryAgentLoader(agent)).resume(financeWaiting.getId(),
            AgentResumeCommand.approveTool("multi-call")
                .withMetadata("approver", "finance-manager"));
        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, complianceWaiting.getStatus());
        assertEquals("COMPLIANCE_APPROVAL",
            complianceWaiting.getSuspension().getApprovalCode());
        assertEquals(0, sideEffects.get());

        AgentTurn completed = new AgentRunner(
            store, new InMemoryAgentLoader(agent)).resume(complianceWaiting.getId(),
            AgentResumeCommand.approveTool("multi-call")
                .withMetadata("approver", "compliance-manager"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, sideEffects.get());
        assertEquals(3, contexts.size());
        AgentToolContext finalContext = contexts.get(2);
        assertTrue(finalContext.isToolApproved(financeRequest));
        assertTrue(finalContext.isToolApproved(complianceRequest));
        assertEquals(2, finalContext.getToolApprovalRecords().size());
        assertEquals("finance-manager", finalContext.getToolApprovalRecord(financeRequest)
            .getResponseMetadata().get("approver"));
        assertEquals("compliance-manager", finalContext.getToolApprovalRecord(complianceRequest)
            .getResponseMetadata().get("approver"));
        assertEquals(3, finalContext.getExecutionAttempt());
        assertEquals(1, completed.getToolCallCount());

        Map<String, ToolApprovalRecord> persisted = completed.toSnapshot().getState()
            .getToolApprovalRecordsByRequest().get("multi-call");
        assertEquals(2, persisted.size());
        assertTrue(persisted.get(financeRequest.getRequestFingerprint()).isApproved());
        assertTrue(persisted.get(complianceRequest.getRequestFingerprint()).isApproved());
        assertNotNull(completed.getMetadata().get("toolApprovalAudit.multi-call.TOOL."
            + financeRequest.getRequestFingerprint()));
        assertNotNull(completed.getMetadata().get("toolApprovalAudit.multi-call.TOOL."
            + complianceRequest.getRequestFingerprint()));
        try {
            finalContext.getToolApprovalRecords().clear();
            fail("multi-level approval records must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 预期：Tool 只能读取批准记录，不能在业务代码中伪造或删除批准。
        }
    }

    @Test
    public void rejectionAtLaterApprovalLevelStopsToolAndPreservesEarlierApproval() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("multi-reject-call", "release_contract", "{}")));
        model.enqueue(prompt -> new AiMessage("contract release rejected"));
        ToolApprovalDecision ownerRequest = ToolApprovalDecision.requireApproval()
            .code("OWNER_APPROVAL").message("业务负责人审批").build();
        ToolApprovalDecision legalRequest = ToolApprovalDecision.requireApproval()
            .code("LEGAL_APPROVAL").message("法务审批").build();
        AtomicInteger sideEffects = new AtomicInteger();
        Tool tool = approvalRequestingTool("release_contract", arguments -> {
            AgentToolContext context = AgentToolContext.current();
            if (!context.isToolApproved(ownerRequest)) {
                throw new AgentApprovalRequiredException(ownerRequest);
            }
            if (!context.isToolApproved(legalRequest)) {
                throw new AgentApprovalRequiredException(legalRequest);
            }
            sideEffects.incrementAndGet();
            return "released";
        });
        Agent agent = Agent.builder("multi-level-rejection")
            .chatModel(model).tool(tool).build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();

        AgentTurn first = new AgentRunner(
            store, new InMemoryAgentLoader(agent)).run(agent, "release contract");
        AgentTurn second = new AgentRunner(
            store, new InMemoryAgentLoader(agent)).resume(first.getId(),
            AgentResumeCommand.approveTool("multi-reject-call")
                .withMetadata("approver", "business-owner"));
        AgentTurn completed = new AgentRunner(
            store, new InMemoryAgentLoader(agent)).resume(second.getId(),
            AgentResumeCommand.rejectTool("multi-reject-call", "合同条款不符合要求")
                .withMetadata("approver", "legal-manager"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(0, sideEffects.get());
        Map<String, ToolApprovalRecord> records = completed.toSnapshot().getState()
            .getToolApprovalRecordsByRequest().get("multi-reject-call");
        assertEquals(2, records.size());
        assertTrue(records.get(ownerRequest.getRequestFingerprint()).isApproved());
        assertFalse(records.get(legalRequest.getRequestFingerprint()).isApproved());
        assertEquals("合同条款不符合要求",
            records.get(legalRequest.getRequestFingerprint()).getRejectionReason());
    }

    @Test
    public void requestingAnyPreviouslyApprovedLevelAgainFailsInsteadOfLooping() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("repeated-level-call", "misconfigured_tool", "{}")));
        ToolApprovalDecision firstRequest = ToolApprovalDecision.requireApproval()
            .code("FIRST_LEVEL").message("第一级审批").build();
        ToolApprovalDecision secondRequest = ToolApprovalDecision.requireApproval()
            .code("SECOND_LEVEL").message("第二级审批").build();
        AtomicInteger executions = new AtomicInteger();
        Tool tool = approvalRequestingTool("misconfigured_tool", arguments -> {
            int attempt = executions.incrementAndGet();
            if (attempt == 1) throw new AgentApprovalRequiredException(firstRequest);
            if (attempt == 2) throw new AgentApprovalRequiredException(secondRequest);
            // 模拟业务 Tool 忘记调用 isToolApproved(firstRequest)，错误地重复抛出已批准的第一级。
            throw new AgentApprovalRequiredException(firstRequest);
        });
        Agent agent = Agent.builder("repeated-approval-level")
            .chatModel(model).tool(tool).build();
        AgentRunner runner = new AgentRunner();

        AgentTurn first = runner.run(agent, "run");
        AgentTurn second = runner.resume(first,
            AgentResumeCommand.approveTool("repeated-level-call"));
        AgentTurn failed = runner.resume(second,
            AgentResumeCommand.approveTool("repeated-level-call"));

        assertEquals(AgentTurnStatus.FAILED, failed.getStatus());
        assertNotNull(failed.getError());
        assertTrue(failed.getError().getMessage().contains("same approval"));
        assertEquals(3, executions.get());
    }

    @Test
    public void centralPolicyApprovalDoesNotBypassToolRequestedApproval() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("protected-call", "protected_write", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        AtomicInteger toolExecutions = new AtomicInteger();
        List<AgentToolContext> contexts = new ArrayList<>();
        ToolApprovalDecision request = ToolApprovalDecision.requireApproval()
            .code("TOOL_RISK")
            .message("只读预检确认风险后再次审批")
            .metadata("risk", "high")
            .build();
        Tool tool = approvalRequestingTool("protected_write", arguments -> {
            AgentToolContext context = AgentToolContext.current();
            contexts.add(context);
            toolExecutions.incrementAndGet();
            if (!context.isToolApproved(request)) {
                throw new AgentApprovalRequiredException(request);
            }
            return "written";
        });
        Agent agent = Agent.builder("central-policy-first")
            .chatModel(model)
            .tool(tool)
            .toolApprovalPolicy((turn, call, value) ->
                ToolApprovalDecision.requireApproval()
                    .code("CENTRAL_POLICY")
                    .message("中央策略要求审批")
                    .reason("生产环境写操作")
                    .build())
            .build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent, "write");

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals("CENTRAL_POLICY", waiting.getSuspension().getApprovalCode());
        assertEquals(ToolApprovalStage.POLICY, waiting.getSuspension().getApprovalStage());
        // 中央策略没有放行时 Tool 函数完全不会进入，Tool 内逻辑没有机会削弱或替代中央审批。
        assertEquals(0, toolExecutions.get());

        AgentTurn toolApprovalWaiting = runner.resume(waiting,
            AgentResumeCommand.approveTool("protected-call"));

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, toolApprovalWaiting.getStatus());
        assertEquals(ToolApprovalStage.TOOL,
            toolApprovalWaiting.getSuspension().getApprovalStage());
        assertEquals(1, toolExecutions.get());
        assertTrue(contexts.get(0).isPolicyApproved());
        // 中央策略虽已批准，但 Tool 的本次只读预检请求尚未批准，两个阶段不能互相替代。
        assertFalse(contexts.get(0).isToolApproved(request));
        // 中央审批发生在 Tool 之外，因此批准后的执行仍是 Tool 的第一次真实执行，而不是重放。
        assertFalse(contexts.get(0).isReplay());
        assertEquals(1, contexts.get(0).getExecutionAttempt());

        AgentTurn completed = runner.resume(toolApprovalWaiting,
            AgentResumeCommand.approveTool("protected-call")
                .withMetadata("approver", "risk-manager"));
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(2, toolExecutions.get());
        assertTrue(contexts.get(1).isPolicyApproved());
        assertTrue(contexts.get(1).isToolApproved(request));
        assertEquals("risk-manager", contexts.get(1).getToolApprovalRecord()
            .getResponseMetadata().get("approver"));
        Map<ToolApprovalStage, ToolApprovalRecord> records = completed.toSnapshot()
            .getState().getToolApprovalRecords().get("protected-call");
        assertEquals(2, records.size());
        assertTrue(records.get(ToolApprovalStage.POLICY).isApproved());
        assertTrue(records.get(ToolApprovalStage.TOOL).isApproved());
    }

    @Test
    public void centralPolicyDenialPreventsToolFromStarting() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("denied-call", "delete_data", "{}")));
        model.enqueue(prompt -> new AiMessage("denial handled"));
        AtomicInteger toolExecutions = new AtomicInteger();
        Agent agent = Agent.builder("central-policy-deny")
            .chatModel(model)
            .tool(approvalRequestingTool("delete_data", arguments -> {
                toolExecutions.incrementAndGet();
                return "deleted";
            }))
            .toolApprovalPolicy((turn, call, value) ->
                ToolApprovalDecision.deny().code("BLOCKED_BY_POLICY").build())
            .build();

        AgentTurn completed = new AgentRunner().run(agent, "delete");

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(0, toolExecutions.get());
    }

    @Test
    public void rejectedToolApprovalNeverExecutesSideEffect() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("publish-call", "publish", "{}")));
        model.enqueue(prompt -> new AiMessage("publish rejected"));
        AtomicInteger sideEffects = new AtomicInteger();
        Tool publish = approvalRequestingTool("publish", arguments -> {
            AgentToolContext context = AgentToolContext.current();
            ToolApprovalDecision request = ToolApprovalDecision.requireApproval()
                .code("PUBLIC_RELEASE").message("是否公开发布？").build();
            if (!context.isToolApproved(request)) {
                throw new AgentApprovalRequiredException(request);
            }
            sideEffects.incrementAndGet();
            return "published";
        });
        Agent agent = Agent.builder("tool-approval-rejection")
            .chatModel(model).tool(publish).build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent, "publish");
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.rejectTool("publish-call", "内容仍需修改"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(0, sideEffects.get());
    }

    @Test
    public void ordinaryParallelToolCanRequestApprovalWithoutMetadata() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("first-call", "approval_first", "{}"),
            new ToolCall("second-call", "write_second", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<String>());
        Tool first = approvalRequestingTool("approval_first", arguments -> {
            AgentToolContext context = AgentToolContext.current();
            executionOrder.add("first-preflight");
            ToolApprovalDecision request = ToolApprovalDecision.requireApproval()
                .message("approve first").build();
            if (!context.isToolApproved(request)) {
                throw new AgentApprovalRequiredException(request);
            }
            executionOrder.add("first-write");
            return "first";
        });
        Tool second = AgentScenarioTestSupport.tool("write_second", arguments -> {
            executionOrder.add("second-write");
            return "second";
        });
        Agent agent = Agent.builder("parallel-tool-approval")
            .chatModel(model)
            .tool(first)
            .tool(second)
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolExecutionMode(AgentToolExecutionMode.PARALLEL)
                .maxParallelToolCalls(2)
                .build())
            .build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent, "run both");

        // 并行批次已经启动的独立 Tool 会正常完成；审批只保护 first 自己位于异常之后的写操作。
        assertTrue(executionOrder.contains("first-preflight"));
        assertTrue(executionOrder.contains("second-write"));
        assertFalse(executionOrder.contains("first-write"));
        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());

        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.approveTool("first-call"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(2, Collections.frequency(executionOrder, "first-preflight"));
        assertEquals(1, Collections.frequency(executionOrder, "first-write"));
        assertEquals(1, Collections.frequency(executionOrder, "second-write"));
    }

    @Test
    public void approvedStateSurvivesLaterFormResumeForSameToolCall() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("combined-call", "approve_then_form", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        AgentFormDefinition form = AgentFormDefinition.builder("extra-details")
            .description("补充执行信息")
            .schema(schema)
            .build();
        List<AgentToolContext> contexts = new ArrayList<>();
        AtomicInteger sideEffects = new AtomicInteger();
        Tool tool = approvalRequestingTool("approve_then_form", arguments -> {
            AgentToolContext context = AgentToolContext.current();
            contexts.add(context);
            ToolApprovalDecision request = ToolApprovalDecision.requireApproval()
                .message("approve").build();
            if (!context.isToolApproved(request)) {
                throw new AgentApprovalRequiredException(request);
            }
            if (context.getSubmittedFormData().isEmpty()) {
                throw new AgentFormRequiredException(form);
            }
            sideEffects.incrementAndGet();
            return "ok";
        });
        Agent agent = Agent.builder("approval-form-chain")
            .chatModel(model).tool(tool).build();
        AgentRunner runner = new AgentRunner();

        AgentTurn approval = runner.run(agent, "run");
        AgentTurn formWaiting = runner.resume(approval,
            AgentResumeCommand.approveTool("combined-call")
                .withMetadata("approver", "manager-3"));
        // resume(AgentTurn, ...) 会继续推进传入的可变 Turn，因此必须在下一次恢复前确认中间状态。
        assertEquals(AgentTurnStatus.WAITING_FOR_USER, formWaiting.getStatus());
        AgentTurn completed = runner.resume(formWaiting,
            AgentResumeCommand.userInput("combined-call", mapOf("ticket", "T-9")));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(3, contexts.size());
        AgentToolContext finalContext = contexts.get(2);
        ToolApprovalRecord approvalRecord = finalContext.getToolApprovalRecord();
        assertNotNull(approvalRecord);
        assertTrue(approvalRecord.isApproved());
        assertEquals("manager-3", approvalRecord.getResponseMetadata().get("approver"));
        assertTrue(finalContext.isFormInputResumed());
        assertEquals(AgentToolResumeType.FORM_INPUT, finalContext.getResumeType());
        assertEquals(3, finalContext.getExecutionAttempt());
        assertEquals(1, sideEffects.get());
    }

    @Test
    public void changedPreflightSnapshotInvalidatesPreviousToolApproval() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("amount-call", "amount_write", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        AtomicInteger amount = new AtomicInteger(20000);
        AtomicInteger sideEffects = new AtomicInteger();
        Tool tool = approvalRequestingTool("amount_write", arguments -> {
            AgentToolContext context = AgentToolContext.current();
            ToolApprovalDecision currentRequest = ToolApprovalDecision.requireApproval()
                .code("AMOUNT_WRITE")
                .message("确认当前金额")
                // 金额来自每次恢复时重新执行的只读查询，因此变化后会生成不同指纹。
                .metadata("amount", amount.get())
                .build();
            if (!context.isToolApproved(currentRequest)) {
                throw new AgentApprovalRequiredException(currentRequest);
            }
            sideEffects.incrementAndGet();
            return "ok";
        });
        Agent agent = Agent.builder("snapshot-bound-approval")
            .chatModel(model).tool(tool).build();
        AgentRunner runner = new AgentRunner();

        AgentTurn firstRequest = runner.run(agent, "write");
        String firstFingerprint = firstRequest.getSuspension()
            .getApprovalRequestFingerprint();
        amount.set(30000);
        AgentTurn secondRequest = runner.resume(firstRequest,
            AgentResumeCommand.approveTool("amount-call"));

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, secondRequest.getStatus());
        String secondFingerprint = secondRequest.getSuspension()
            .getApprovalRequestFingerprint();
        assertFalse(firstFingerprint.equals(secondFingerprint));
        assertEquals(0, sideEffects.get());

        AgentTurn completed = runner.resume(secondRequest,
            AgentResumeCommand.approveTool("amount-call"));
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, sideEffects.get());
        Map<String, ToolApprovalRecord> records = completed.toSnapshot().getState()
            .getToolApprovalRecordsByRequest().get("amount-call");
        assertEquals(2, records.size());
        assertTrue(records.get(firstFingerprint).isApproved());
        assertTrue(records.get(secondFingerprint).isApproved());
    }

    @Test
    public void wrappedControlFlowExceptionStillSuspendsTurn() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("wrapped-call", "wrapped", "{}")));
        Tool tool = approvalRequestingTool("wrapped", arguments -> {
            throw new AgentApprovalRequiredException(
                ToolApprovalDecision.requireApproval().message("wrapped approval").build());
        });
        AgentMiddleware wrapper = new AgentMiddleware() {
            @Override
            public Object aroundToolCall(AgentMiddlewareContext context,
                                         AgentToolCallChain chain) {
                try {
                    return chain.proceed(context);
                } catch (RuntimeException cause) {
                    throw new IllegalStateException("middleware context", cause);
                }
            }
        };
        Agent agent = Agent.builder("wrapped-control-flow")
            .chatModel(model).tool(tool).middleware(wrapper).build();

        AgentTurn waiting = new AgentRunner().run(agent, "run");

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals(ToolApprovalStage.TOOL, waiting.getSuspension().getApprovalStage());
    }

    @Test
    public void policyExceptionFailsClosedThroughRunnerStateMachine() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("policy-error", "write", "{}")));
        AtomicInteger executions = new AtomicInteger();
        Agent agent = Agent.builder("policy-fail-closed")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("write", arguments -> executions.incrementAndGet()))
            .toolApprovalPolicy((turn, call, tool) -> {
                throw new IllegalStateException("policy service unavailable");
            })
            .build();

        AgentTurn failed = new AgentRunner().run(agent, "write");

        assertEquals(AgentTurnStatus.FAILED, failed.getStatus());
        assertEquals(0, executions.get());
        assertTrue(failed.getError().getMessage().contains("policy service unavailable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void approvalMetadataIsDeeplyImmutableAndFingerprintIsOrderIndependent() {
        Map<String, Object> nestedA = new LinkedHashMap<>();
        nestedA.put("items", new ArrayList<Object>(Arrays.<Object>asList("a", "b")));
        nestedA.put("amount", 100);
        Map<String, Object> nestedB = new LinkedHashMap<>();
        nestedB.put("amount", 100);
        nestedB.put("items", Arrays.<Object>asList("a", "b"));
        ToolApprovalDecision first = ToolApprovalDecision.requireApproval()
            .code("STABLE").metadata(nestedA).build();
        ToolApprovalDecision second = ToolApprovalDecision.requireApproval()
            .code("STABLE").metadata(nestedB).build();

        assertEquals(first.getRequestFingerprint(), second.getRequestFingerprint());
        ((List<Object>) nestedA.get("items")).add("changed-after-build");
        assertEquals(2, ((List<?>) first.getMetadata().get("items")).size());
        try {
            ((List<Object>) first.getMetadata().get("items")).add("forbidden");
            fail("nested approval metadata must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 预期：等待审批期间调用方不能篡改嵌套快照。
        }
    }

    @Test
    public void approvalExceptionOnlyAcceptsRequireApprovalDecision() {
        assertInvalidDecision(null);
        assertInvalidDecision(ToolApprovalDecision.ALLOW);
        assertInvalidDecision(ToolApprovalDecision.DENY);
        AgentApprovalRequiredException valid = new AgentApprovalRequiredException(
            ToolApprovalDecision.requireApproval().message("review").build());
        assertEquals("review", valid.getMessage());
    }

    @Test
    public void ordinaryToolCanRequestApprovalWithoutMetadataDeclaration() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("undeclared-call", "undeclared", "{}")));
        Tool ordinary = Tool.builder("undeclared", "ordinary local tool")
            .function(arguments -> {
                throw new AgentApprovalRequiredException(
                    ToolApprovalDecision.requireApproval().message("approve").build());
            })
            .build();
        Agent agent = Agent.builder("ordinary-tool-approval")
            .chatModel(model).tool(ordinary).build();

        AgentTurn waiting = new AgentRunner().run(agent, "run");

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals(ToolApprovalStage.TOOL, waiting.getSuspension().getApprovalStage());
    }

    @Test
    public void suspensionBaseClassDoesNotExposeApprovalProtocol() throws Exception {
        // 基类只负责标识 Tool 控制流暂停，不能被业务代码直接实例化为一种含义不明的暂停请求。
        assertTrue(Modifier.isAbstract(AgentToolSuspensionException.class.getModifiers()));

        // ToolApprovalDecision 只能由审批子类持有；这个契约可防止表单等未来子类再次出现 null decision。
        assertFalse(Arrays.stream(AgentToolSuspensionException.class.getDeclaredFields())
            .anyMatch(field -> field.getType() == ToolApprovalDecision.class));
        assertFalse(Arrays.stream(AgentToolSuspensionException.class.getDeclaredConstructors())
            .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
            .anyMatch(type -> type == ToolApprovalDecision.class));
        assertEquals(AgentApprovalRequiredException.class,
            AgentApprovalRequiredException.class.getMethod("getDecision").getDeclaringClass());
    }

    private static Tool approvalRequestingTool(
        String name, java.util.function.Function<Map<String, Object>, Object> function) {
        return Tool.builder(name, "may request approval after a read-only preflight")
            .function(function)
            .build();
    }

    private static void assertInvalidDecision(ToolApprovalDecision decision) {
        try {
            new AgentApprovalRequiredException(decision);
            fail("non-approval decision must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("REQUIRE_APPROVAL"));
        }
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}

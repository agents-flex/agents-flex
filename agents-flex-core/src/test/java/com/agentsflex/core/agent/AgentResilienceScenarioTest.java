/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.registry.InMemoryAgentRegistry;
import com.agentsflex.core.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.core.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.core.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 验证审批、重试、Checkpoint 与预算共同工作时的执行语义。 */
public class AgentResilienceScenarioTest {

    @Test
    public void shouldApproveFromAnotherRunnerAndExecuteSideEffectOnce() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("payment-1", "payment", "{}")));
        model.enqueue(prompt -> new AiMessage("paid"));
        Agent agent = Agent.builder("durable-approval-agent")
            .chatModel(model)
            .tool(tool("payment", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        AgentRunner firstRunner = new AgentRunner(store, registry);
        AgentRun waiting = firstRunner.run(agent, "pay");

        AgentRunner secondRunner = new AgentRunner(store, registry);
        AgentRun completed = secondRunner.resume(waiting.getId(),
            AgentResumeCommand.approveTool("payment-1"));
        AgentRun restoredAgain = firstRunner.runUntilBlocked(completed.getId());

        assertEquals(AgentRunStatus.COMPLETED, restoredAgain.getStatus());
        assertEquals(1, executions.get());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldPersistRejectedApprovalAndReturnToolMessageAcrossRunners() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("delete-1", "delete", "{}")));
        model.enqueue(prompt -> {
            Message last = prompt.getMessages().get(prompt.getMessages().size() - 1);
            assertTrue(last instanceof ToolMessage);
            assertTrue(last.getTextContent().contains("tool_rejected"));
            return new AiMessage("kept");
        });
        Agent agent = Agent.builder("durable-rejection-agent")
            .chatModel(model)
            .tool(tool("delete", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        AgentRun waiting = new AgentRunner(store, registry).run(agent, "delete");

        AgentRun completed = new AgentRunner(store, registry).resume(waiting.getId(),
            AgentResumeCommand.rejectTool("delete-1", "protected resource"));

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("kept", completed.getFinalOutput());
        assertEquals(0, executions.get());
        assertEquals("protected resource",
            completed.getMetadata().get("toolRejectionReason.delete-1"));
    }

    @Test
    public void shouldRetrySamePendingToolThroughWorkerWithoutRepeatingModelDecision() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger attempts = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("unstable-1", "unstable", "{}")));
        model.enqueue(prompt -> new AiMessage("recovered"));
        Agent agent = Agent.builder("tool-retry-agent")
            .chatModel(model)
            .tool(tool("unstable", args -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new RuntimeException("temporary tool failure");
                }
                return "ok";
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder()
                    .maxRetries(1)
                    .initialDelayMillis(0)
                    .maxDelayMillis(0)
                    .build())
                .build())
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentRegistry());

        AgentRun scheduled = runner.run(agent, "execute");
        assertEquals(AgentRunStatus.RETRY_SCHEDULED, scheduled.getStatus());
        assertEquals(AgentRunPhase.TOOLS, scheduled.getPhase());
        assertEquals(1, scheduled.getPendingToolCalls().size());

        List<AgentRun> processed = new AgentWorker("retry-worker", runner, 10000).pollAndRun(1);

        assertEquals(1, processed.size());
        assertEquals(AgentRunStatus.COMPLETED, processed.get(0).getStatus());
        assertEquals(2, attempts.get());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldLetTimeBudgetStopScheduledRetryBeforeSecondSideEffect() throws Exception {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger attempts = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("slow-1", "slow", "{}")));
        Agent agent = Agent.builder("retry-budget-agent")
            .chatModel(model)
            .tool(tool("slow", args -> {
                attempts.incrementAndGet();
                throw new RuntimeException("temporary");
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder()
                    .maxRetries(1)
                    .initialDelayMillis(5)
                    .maxDelayMillis(5)
                    .build())
                .budget(AgentBudget.builder().maxDurationMillis(100).build())
                .build())
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentRegistry());
        AgentRun scheduled = runner.run(agent, "execute");
        assertEquals(AgentRunStatus.RETRY_SCHEDULED, scheduled.getStatus());

        Thread.sleep(150);
        List<AgentRun> processed = new AgentWorker("budget-worker", runner, 10000).pollAndRun(1);

        assertEquals(1, processed.size());
        assertEquals(AgentRunStatus.BUDGET_EXCEEDED, processed.get(0).getStatus());
        assertEquals("maxDurationMillis", processed.get(0).getBudgetExceededReason());
        assertEquals(1, attempts.get());
    }

    @Test
    public void shouldStopTokenHeavyToolDecisionBeforeAnySideEffect() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> {
            AiMessage message = toolCalls(new ToolCall("expensive-1", "expensive", "{}"));
            message.setPromptTokens(8);
            message.setCompletionTokens(5);
            message.setTotalTokens(13);
            return message;
        });
        Agent agent = Agent.builder("token-tool-budget-agent")
            .chatModel(model)
            .tool(tool("expensive", args -> executions.incrementAndGet()))
            .executionPolicy(AgentExecutionPolicy.builder()
                .budget(AgentBudget.builder().maxTotalTokens(10).build())
                .build())
            .build();

        AgentRun run = new AgentRunner().run(agent, "execute");

        assertEquals(AgentRunStatus.BUDGET_EXCEEDED, run.getStatus());
        assertEquals("maxTotalTokens", run.getBudgetExceededReason());
        assertEquals(0, executions.get());
        assertEquals(13, run.getTotalTokens());
    }
}

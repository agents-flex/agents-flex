/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证 Worker、租约和事件监听器组成的长任务执行链路。 */
public class AgentLongRunningScenarioTest {

    @Test
    public void shouldAllowAnotherWorkerToClaimOnlyAfterLeaseExpires() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("lease-expiry-agent").chatModel(model).build();
        AgentTurn turn = new AgentRunner(store, new InMemoryAgentLoader()).start(agent, "work");
        long now = System.currentTimeMillis();

        List<AgentTurnSnapshot> first = store.claimRunnable("worker-a", now, 1000, 1);
        List<AgentTurnSnapshot> beforeExpiry =
            store.claimRunnable("worker-b", now + 999, 1000, 1);
        List<AgentTurnSnapshot> afterExpiry =
            store.claimRunnable("worker-b", now + 1001, 1000, 1);

        assertEquals(1, first.size());
        assertTrue(beforeExpiry.isEmpty());
        assertEquals(1, afterExpiry.size());
        assertEquals(turn.getId(), afterExpiry.get(0).getState().getTurnId());
        assertEquals("worker-b", afterExpiry.get(0).getState().getLeaseOwner());
    }

    @Test
    public void shouldObserveApprovalWorkflowAcrossRunners() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("event-call", "event-tool", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("event-agent")
            .chatModel(model)
            .tool(tool("event-tool", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((turn, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        InMemoryAgentTurnStore turnStore = new InMemoryAgentTurnStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner firstRunner = new AgentRunner(turnStore, registry).addEventListener(events::add);
        AgentTurn waiting = firstRunner.run(agent, "execute");

        AgentRunner secondRunner = new AgentRunner(turnStore, registry).addEventListener(events::add);
        AgentTurn completed = secondRunner.resume(waiting.getId(),
            AgentResumeCommand.approveTool("event-call")
                .withMetadata("approverId", "admin-7"));

        assertFalse(events.isEmpty());
        assertBefore(events, AgentEventType.TOOL_APPROVAL_REQUESTED,
            AgentEventType.TURN_RESUMED);
        assertBefore(events, AgentEventType.TURN_RESUMED, AgentEventType.TOOL_STARTED);
        assertBefore(events, AgentEventType.TOOL_STARTED, AgentEventType.TOOL_COMPLETED);
        assertBefore(events, AgentEventType.TOOL_COMPLETED, AgentEventType.TURN_COMPLETED);
        AgentEvent toolStarted = find(events, AgentEventType.TOOL_STARTED);
        assertEquals("event-call", toolStarted.getData().get("toolCallId"));
        assertEquals("event-tool", toolStarted.getData().get("toolName"));
        assertEquals("admin-7", ((java.util.Map<?, ?>) completed.getMetadata()
            .get("lastResumeCommandMetadata")).get("approverId"));
        assertEquals(1, executions.get());
    }

    @Test
    public void shouldPersistentlyCancelWaitingRunAndLetWorkerFinalizeIt() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(
            new ToolCall("cancel-call", "danger", "{}")));
        Agent agent = Agent.builder("cancel-agent")
            .chatModel(model)
            .tool(tool("danger", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((turn, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        InMemoryAgentTurnStore turnStore = new InMemoryAgentTurnStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner requestRunner = new AgentRunner(turnStore, registry)
            .addEventListener(events::add);
        AgentTurn waiting = requestRunner.run(agent, "execute");

        AgentRunner workerRunner = new AgentRunner(turnStore, registry)
            .addEventListener(events::add);
        AgentTurn requested = workerRunner.requestCancellation(waiting.getId());
        assertTrue(requested.isCancellationRequested());
        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, requested.getStatus());

        List<AgentTurn> processed;
        try (AgentWorker worker = new AgentWorker("cancel-worker", workerRunner, 30_000)) {
            processed = worker.pollAndRun(1);
        }

        assertEquals(1, processed.size());
        assertEquals(AgentTurnStatus.CANCELLED, processed.get(0).getStatus());
        assertEquals(0, executions.get());
        assertBefore(events, AgentEventType.CANCELLATION_REQUESTED,
            AgentEventType.TURN_CANCELLED);
    }

    @Test
    public void shouldKeepCancellationSignalWhenStaleRunSavesNextSnapshot() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("cancel-merge-agent").chatModel(model).build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentTurn stale = runner.start(agent, "work");

        runner.requestCancellation(stale.getId());
        AgentTurnSnapshot saved = runner.saveSnapshot(stale);

        assertTrue(saved.getState().isCancellationRequested());
        assertTrue(stale.isCancellationRequested());
        assertTrue(store.load(stale.getId()).getState().isCancellationRequested());
    }

    private void assertBefore(List<AgentEvent> events, AgentEventType first,
                              AgentEventType second) {
        assertTrue(first + " should occur before " + second,
            indexOf(events, first) < indexOf(events, second));
    }

    private int indexOf(List<AgentEvent> events, AgentEventType type) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getType() == type) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private AgentEvent find(List<AgentEvent> events, AgentEventType type) {
        for (AgentEvent event : events) {
            if (event.getType() == type) {
                return event;
            }
        }
        throw new AssertionError("Event not found: " + type);
    }
}

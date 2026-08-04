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
import com.agentsflex.agent.store.InMemoryAgentRunStore;
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

/** 验证 Worker、租约、子任务和事件监听器组成的长任务执行链路。 */
public class AgentLongRunningScenarioTest {

    @Test
    public void shouldAllowAnotherWorkerToClaimOnlyAfterLeaseExpires() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("lease-expiry-agent").chatModel(model).build();
        AgentRun run = new AgentRunner(store, new InMemoryAgentLoader()).start(agent, "work");
        long now = System.currentTimeMillis();

        List<AgentRunSnapshot> first = store.claimRunnable("worker-a", now, 1000, 1);
        List<AgentRunSnapshot> beforeExpiry =
            store.claimRunnable("worker-b", now + 999, 1000, 1);
        List<AgentRunSnapshot> afterExpiry =
            store.claimRunnable("worker-b", now + 1001, 1000, 1);

        assertEquals(1, first.size());
        assertTrue(beforeExpiry.isEmpty());
        assertEquals(1, afterExpiry.size());
        assertEquals(run.getId(), afterExpiry.get(0).getState().getRunId());
        assertEquals("worker-b", afterExpiry.get(0).getState().getLeaseOwner());
    }

    @Test
    public void shouldCompleteChildAndResumeParentThroughWorkers() {
        AgentScenarioTestSupport.QueueChatModel parentModel =
            new AgentScenarioTestSupport.QueueChatModel();
        AgentScenarioTestSupport.QueueChatModel childModel =
            new AgentScenarioTestSupport.QueueChatModel();
        parentModel.enqueue(prompt -> {
            assertTrue(prompt.getMessages().stream()
                .anyMatch(message -> message.getTextContent().contains("child output")));
            return new AiMessage("parent output");
        });
        childModel.enqueue(prompt -> new AiMessage("child output"));
        Agent parentAgent = Agent.builder("worker-parent").chatModel(parentModel).build();
        Agent childAgent = Agent.builder("worker-child").chatModel(childModel).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(parentAgent, childAgent);
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, registry);
        AgentRun parent = runner.start(parentAgent, "parent input");
        AgentRun child = runner.startChild(parent, childAgent.getId(), "child input");
        AgentWorker worker = new AgentWorker("child-worker", runner, 10000);

        List<AgentRun> childResult = worker.pollAndRun(1);
        AgentRun resumedParent = runner.restore(parent.getId());
        List<AgentRun> parentResult = worker.pollAndRun(1);

        assertEquals(1, childResult.size());
        assertEquals(child.getId(), childResult.get(0).getId());
        assertEquals(AgentRunStatus.COMPLETED, childResult.get(0).getStatus());
        assertEquals(AgentRunStatus.RUNNING, resumedParent.getStatus());
        assertEquals(1, parentResult.size());
        assertEquals(parent.getId(), parentResult.get(0).getId());
        assertEquals(AgentRunStatus.COMPLETED, parentResult.get(0).getStatus());
        assertEquals("parent output", parentResult.get(0).getFinalOutput());
    }

    @Test
    public void shouldIgnoreLateDuplicateChildCompletion() {
        AgentScenarioTestSupport.QueueChatModel parentModel =
            new AgentScenarioTestSupport.QueueChatModel();
        AgentScenarioTestSupport.QueueChatModel childModel =
            new AgentScenarioTestSupport.QueueChatModel();
        childModel.enqueue(prompt -> new AiMessage("child output"));
        Agent parentAgent = Agent.builder("idempotent-parent").chatModel(parentModel).build();
        Agent childAgent = Agent.builder("idempotent-child").chatModel(childModel).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(parentAgent, childAgent);
        AgentRunner runner = new AgentRunner(new InMemoryAgentRunStore(), registry);
        AgentRun parent = runner.start(parentAgent, "parent input");
        AgentRun child = runner.startChild(parent, childAgent.getId(), "child input");
        child = runner.runUntilBlocked(child);

        AgentRun firstResume = runner.resumeParentFromChild(child);
        AgentRun duplicateResume = runner.resumeParentFromChild(child);

        assertEquals(AgentRunStatus.RUNNING, firstResume.getStatus());
        assertEquals(AgentRunStatus.RUNNING, duplicateResume.getStatus());
        assertEquals(1, countChildResultMessages(duplicateResume.getPrompt().getMessages()));
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
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner firstRunner = new AgentRunner(runStore, registry).addEventListener(events::add);
        AgentRun waiting = firstRunner.run(agent, "execute");

        AgentRunner secondRunner = new AgentRunner(runStore, registry).addEventListener(events::add);
        AgentRun completed = secondRunner.resume(waiting.getId(),
            AgentResumeCommand.approveTool("event-call")
                .withMetadata("approverId", "admin-7"));

        assertFalse(events.isEmpty());
        assertBefore(events, AgentEventType.TOOL_APPROVAL_REQUESTED,
            AgentEventType.RUN_RESUMED);
        assertBefore(events, AgentEventType.RUN_RESUMED, AgentEventType.TOOL_STARTED);
        assertBefore(events, AgentEventType.TOOL_STARTED, AgentEventType.TOOL_COMPLETED);
        assertBefore(events, AgentEventType.TOOL_COMPLETED, AgentEventType.RUN_COMPLETED);
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
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner requestRunner = new AgentRunner(runStore, registry)
            .addEventListener(events::add);
        AgentRun waiting = requestRunner.run(agent, "execute");

        AgentRunner workerRunner = new AgentRunner(runStore, registry)
            .addEventListener(events::add);
        AgentRun requested = workerRunner.requestCancellation(waiting.getId());
        assertTrue(requested.isCancellationRequested());
        assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL, requested.getStatus());

        List<AgentRun> processed;
        try (AgentWorker worker = new AgentWorker("cancel-worker", workerRunner, 30_000)) {
            processed = worker.pollAndRun(1);
        }

        assertEquals(1, processed.size());
        assertEquals(AgentRunStatus.CANCELLED, processed.get(0).getStatus());
        assertEquals(0, executions.get());
        assertBefore(events, AgentEventType.CANCELLATION_REQUESTED,
            AgentEventType.RUN_CANCELLED);
    }

    @Test
    public void shouldKeepCancellationSignalWhenStaleRunSavesNextSnapshot() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("cancel-merge-agent").chatModel(model).build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentRun stale = runner.start(agent, "work");

        runner.requestCancellation(stale.getId());
        AgentRunSnapshot saved = runner.saveSnapshot(stale);

        assertTrue(saved.getState().isCancellationRequested());
        assertTrue(stale.isCancellationRequested());
        assertTrue(store.load(stale.getId()).getState().isCancellationRequested());
    }

    private int countChildResultMessages(List<Message> messages) {
        int count = 0;
        for (Message message : messages) {
            if (message.getTextContent() != null
                && message.getTextContent().contains("Child Agent result:")) {
                count++;
            }
        }
        return count;
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

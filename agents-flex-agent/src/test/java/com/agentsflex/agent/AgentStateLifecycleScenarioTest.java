/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentRunVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 验证运行状态、Snapshot 和恢复命令共同构成的生命周期。 */
public class AgentStateLifecycleScenarioTest {

    @Test
    public void shouldRoundTripEveryBlockedStatusThroughSnapshotStore() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("state-agent").chatModel(model).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, registry);
        long now = System.currentTimeMillis();

        AgentRunSnapshot[] snapshots = {
            blocked("user", agent, AgentRunStatus.WAITING_FOR_USER,
                AgentSuspension.userInput("need input"), 0),
            blocked("approval", agent, AgentRunStatus.WAITING_FOR_APPROVAL,
                AgentSuspension.toolApproval("call-1", "danger"), 0),
            blocked("child", agent, AgentRunStatus.WAITING_FOR_CHILD,
                AgentSuspension.child("child-1"), 0),
            blocked("retry", agent, AgentRunStatus.RETRY_SCHEDULED,
                AgentSuspension.retry("temporary", AgentRunPhase.MODEL, now), now)
        };

        for (AgentRunSnapshot snapshot : snapshots) {
            store.save(snapshot, -1);
            AgentRun restored = runner.restore(snapshot.getRunId());
            assertEquals(snapshot.getStatus(), restored.getStatus());
            assertEquals(snapshot.getPhase(), restored.getPhase());
            assertEquals(snapshot.getSuspension().getType(),
                restored.getSuspension().getType());
            assertTrue(restored.getStatus().isBlocked());
            assertFalse(restored.getStatus().isTerminal());
        }
    }

    @Test
    public void shouldRemainBlockedWithoutRepeatingModelCall() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("approval-1", "danger", "{}")));
        Agent agent = Agent.builder("blocked-agent")
            .chatModel(model)
            .tool(tool("danger", args -> "ok"))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent));

        AgentRun waiting = runner.run(agent, "execute");
        AgentRun stillWaiting = runner.runUntilBlocked(waiting.getId());

        assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL, stillWaiting.getStatus());
        assertEquals(1, model.getCallCount());
        assertEquals(1, stillWaiting.getPendingToolCalls().size());
    }

    @Test
    public void shouldSubmitResumeWithoutExecutingUntilRunnerContinues() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("approval-2", "danger", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("submitted-resume-agent")
            .chatModel(model)
            .tool(tool("danger", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner firstRunner = new AgentRunner(store, registry);
        AgentRun waiting = firstRunner.run(agent, "execute");

        AgentRunner commandRunner = new AgentRunner(store, registry);
        AgentRun runnable = commandRunner.submitResume(waiting.getId(),
            AgentResumeCommand.approveTool("approval-2"));

        assertEquals(AgentRunStatus.RUNNING, runnable.getStatus());
        assertEquals(AgentRunPhase.TOOLS, runnable.getPhase());
        assertEquals(0, executions.get());
        assertEquals(1, model.getCallCount());

        AgentRunner executionRunner = new AgentRunner(store, registry);
        AgentRun completed = executionRunner.runUntilBlocked(runnable.getId());
        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(1, executions.get());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldRejectMismatchedAndDuplicateResumeCommands() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("approval-3", "danger", "{}")));
        Agent agent = Agent.builder("resume-validation-agent")
            .chatModel(model)
            .tool(tool("danger", args -> "ok"))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent));
        AgentRun waiting = runner.run(agent, "execute");

        try {
            runner.submitResume(waiting.getId(), AgentResumeCommand.approveTool("other-call"));
            fail("Expected correlation validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("correlationId"));
        }

        AgentRun runnable = runner.submitResume(waiting.getId(),
            AgentResumeCommand.approveTool("approval-3"));
        try {
            runner.submitResume(runnable.getId(),
                AgentResumeCommand.approveTool("approval-3"));
            fail("Expected duplicate resume validation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not blocked"));
        }
    }

    @Test
    public void shouldRejectStaleSnapshotWithoutOverwritingLatestState() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("cas-agent").chatModel(model).build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        AgentRun run = runner.start(agent, "input");
        AgentRunSnapshot stale = store.load(run.getId());
        AgentRunSnapshot latest = store.save(stale.toBuilder()
            .metadata(Collections.<String, Object>singletonMap("owner", "latest"))
            .build(), stale.getVersion());

        try {
            store.save(stale.toBuilder()
                .metadata(Collections.<String, Object>singletonMap("owner", "stale"))
                .build(), stale.getVersion());
            fail("Expected optimistic lock conflict");
        } catch (AgentRunVersionConflictException expected) {
            assertNotNull(expected.getMessage());
        }

        AgentRunSnapshot persisted = store.load(run.getId());
        assertEquals(latest.getVersion(), persisted.getVersion());
        assertEquals("latest", persisted.getMetadata().get("owner"));
    }

    private AgentRunSnapshot blocked(String runId, Agent agent, AgentRunStatus status,
                                     AgentSuspension suspension, long nextRunAt) {
        return AgentRunSnapshot.builder(runId, agent.getId(), agent.getVersion())
            .executionPolicy(agent.getExecutionPolicy())
            .status(status)
            .phase(suspension.getResumePhase())
            .messages(Arrays.asList(new com.agentsflex.core.message.UserMessage("input")))
            .suspension(suspension)
            .createdAt(System.currentTimeMillis())
            .updatedAt(System.currentTimeMillis())
            .nextRunAt(nextRunAt)
            .build();
    }
}

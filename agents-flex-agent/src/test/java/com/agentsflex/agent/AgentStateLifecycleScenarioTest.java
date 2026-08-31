/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
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
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, registry);
        long now = System.currentTimeMillis();

        AgentTurnSnapshot[] snapshots = {
            blocked("user", agent, AgentTurnStatus.WAITING_FOR_USER,
                AgentSuspension.userInput("need input"), 0),
            blocked("approval", agent, AgentTurnStatus.WAITING_FOR_APPROVAL,
                AgentSuspension.toolApproval("call-1", "danger"), 0),
            blocked("retry", agent, AgentTurnStatus.RETRY_SCHEDULED,
                AgentSuspension.retry("temporary", AgentTurnPhase.MODEL, now), now)
        };

        for (AgentTurnSnapshot snapshot : snapshots) {
            store.save(snapshot, -1);
            AgentTurn restored = runner.restore(snapshot.getState().getTurnId());
            assertEquals(snapshot.getState().getStatus(), restored.getStatus());
            assertEquals(snapshot.getState().getPhase(), restored.getPhase());
            assertEquals(snapshot.getState().getSuspension().getType(),
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
            .toolApprovalPolicy((turn, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentTurnStore(), new InMemoryAgentLoader(agent));

        AgentTurn waiting = runner.run(agent, "execute");
        AgentTurn stillWaiting = runner.runUntilBlocked(waiting.getId());

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, stillWaiting.getStatus());
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
            .toolApprovalPolicy((turn, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner firstRunner = new AgentRunner(store, registry);
        AgentTurn waiting = firstRunner.run(agent, "execute");

        AgentRunner commandRunner = new AgentRunner(store, registry);
        AgentTurn runnable = commandRunner.submitResume(waiting.getId(),
            AgentResumeCommand.approveTool("approval-2"));

        assertEquals(AgentTurnStatus.RUNNING, runnable.getStatus());
        assertEquals(AgentTurnPhase.TOOLS, runnable.getPhase());
        assertEquals(0, executions.get());
        assertEquals(1, model.getCallCount());

        AgentRunner executionRunner = new AgentRunner(store, registry);
        AgentTurn completed = executionRunner.runUntilBlocked(runnable.getId());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
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
            .toolApprovalPolicy((turn, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentTurnStore(), new InMemoryAgentLoader(agent));
        AgentTurn waiting = runner.run(agent, "execute");

        try {
            runner.submitResume(waiting.getId(), AgentResumeCommand.approveTool("other-call"));
            fail("Expected correlation validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("correlationId"));
        }

        AgentTurn runnable = runner.submitResume(waiting.getId(),
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
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        AgentTurn turn = runner.start(agent, "input");
        AgentTurnSnapshot stale = store.load(turn.getId());
        AgentTurnSnapshot latest = store.save(stale.withState(stale.getState().toBuilder()
            .metadata(Collections.<String, Object>singletonMap("owner", "latest"))
            .build()), stale.getState().getVersion());

        try {
            store.save(stale.withState(stale.getState().toBuilder()
                .metadata(Collections.<String, Object>singletonMap("owner", "stale"))
                .build()), stale.getState().getVersion());
            fail("Expected optimistic lock conflict");
        } catch (AgentTurnVersionConflictException expected) {
            assertNotNull(expected.getMessage());
        }

        AgentTurnSnapshot persisted = store.load(turn.getId());
        assertEquals(latest.getState().getVersion(), persisted.getState().getVersion());
        assertEquals("latest", persisted.getState().getMetadata().get("owner"));
    }

    private AgentTurnSnapshot blocked(String turnId, Agent agent, AgentTurnStatus status,
                                     AgentSuspension suspension, long nextRunnableAt) {
        AgentTurnState state = AgentTurnState.builder(turnId, agent.getExecutionPolicy(),
                System.currentTimeMillis())
            .status(status)
            .phase(suspension.getResumePhase())
            .messages(Arrays.asList(new com.agentsflex.core.message.UserMessage("input")))
            .suspension(suspension)
            .updatedAt(System.currentTimeMillis())
            .nextRunnableAt(nextRunnableAt)
            .build();
        return AgentTurnSnapshot.of(agent.getId(), agent.getVersion(), state);
    }
}

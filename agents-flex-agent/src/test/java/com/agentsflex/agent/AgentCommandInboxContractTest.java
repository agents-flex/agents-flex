/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.command.AgentRunCommand;
import com.agentsflex.agent.command.AgentRunCommandStatus;
import com.agentsflex.agent.command.InMemoryAgentRunCommandStore;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.message.AiMessage;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Command Inbox 的幂等、租约和失败状态机契约测试。 */
public class AgentCommandInboxContractTest {

    @Test
    public void shouldReclaimCommandAfterLeaseExpires() {
        InMemoryAgentRunCommandStore store = new InMemoryAgentRunCommandStore();
        store.submit(command("c1"));
        AgentRunCommand first = store.claim("worker-a", 100, 20, 1).get(0);

        assertTrue(store.claim("worker-b", 119, 20, 1).isEmpty());
        AgentRunCommand reclaimed = store.claim("worker-b", 120, 20, 1).get(0);

        assertEquals("worker-a", first.getLeaseOwner());
        assertEquals("worker-b", reclaimed.getLeaseOwner());
        assertEquals(2, reclaimed.getAttempts());
    }

    @Test
    public void shouldRejectStateChangesFromNonOwner() {
        InMemoryAgentRunCommandStore store = claimedStore("c1", "worker-a");

        assertIllegalState(() -> store.acknowledge("c1", "worker-b"));
        assertIllegalState(() -> store.release("c1", "worker-b", "error"));
        assertIllegalState(() -> store.fail("c1", "worker-b", "error"));

        assertEquals(AgentRunCommandStatus.CLAIMED, store.load("c1").getStatus());
    }

    @Test
    public void shouldPreserveAttemptsAndLastErrorAcrossRelease() {
        InMemoryAgentRunCommandStore store = claimedStore("c1", "worker-a");
        store.release("c1", "worker-a", "temporary");
        AgentRunCommand released = store.load("c1");

        assertEquals(AgentRunCommandStatus.PENDING, released.getStatus());
        assertEquals(1, released.getAttempts());
        assertEquals("temporary", released.getErrorMessage());
        assertNull(released.getLeaseOwner());
        assertEquals(0, released.getLeaseUntil());
    }

    @Test
    public void shouldCompleteOnlyClaimedCommand() {
        InMemoryAgentRunCommandStore store = claimedStore("c1", "worker-a");
        store.acknowledge("c1", "worker-a");

        AgentRunCommand completed = store.load("c1");
        assertEquals(AgentRunCommandStatus.COMPLETED, completed.getStatus());
        assertEquals(1, completed.getAttempts());
        assertNull(completed.getLeaseOwner());
        assertTrue(store.claim("worker-b", 1000, 10, 10).isEmpty());
    }

    @Test
    public void shouldRespectClaimLimitAndInsertionOrder() {
        InMemoryAgentRunCommandStore store = new InMemoryAgentRunCommandStore();
        store.submit(command("c1"));
        store.submit(command("c2"));
        store.submit(command("c3"));

        List<AgentRunCommand> claimed = store.claim("worker", 100, 20, 2);

        assertEquals(2, claimed.size());
        assertEquals("c1", claimed.get(0).getCommandId());
        assertEquals("c2", claimed.get(1).getCommandId());
        assertEquals(AgentRunCommandStatus.PENDING, store.load("c3").getStatus());
    }

    @Test
    public void shouldReturnExistingCommandForIdenticalSubmission() {
        InMemoryAgentRunCommandStore store = new InMemoryAgentRunCommandStore();
        AgentRunCommand first = store.submit(command("c1"));
        AgentRunCommand duplicate = store.submit(command("c1"));

        assertSame(first, duplicate);
    }

    @Test
    public void shouldRejectConcurrentDifferentSubmissionUsingSameId() throws Exception {
        InMemoryAgentRunCommandStore store = new InMemoryAgentRunCommandStore();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> submit(store, "run-a", start, accepted, rejected));
        executor.submit(() -> submit(store, "run-b", start, accepted, rejected));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, accepted.get());
        assertEquals(1, rejected.get());
        assertNotNull(store.load("same-id"));
    }

    @Test
    public void shouldFailCommandAfterThreeProcessingAttempts() {
        InMemoryAgentRunCommandStore commandStore = new InMemoryAgentRunCommandStore();
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("unused"));
        Agent agent = Agent.builder("command-failure").chatModel(model).build();
        AgentRunner runner = AgentRunner.builder()
            .runStore(new InMemoryAgentRunStore())
            .agentLoader(new InMemoryAgentLoader(agent))
            .commandStore(commandStore)
            .build();
        AgentRun waiting = runner.start(agent, "input");
        runner.suspend(waiting, AgentSuspension.userInput("value"));
        runner.submitCommand("bad", waiting.getId(), AgentResumeCommand.approveTool("wrong"));

        assertEquals(0, runner.processCommands("worker", 100, 1));
        assertEquals(0, runner.processCommands("worker", 100, 1));
        assertEquals(0, runner.processCommands("worker", 100, 1));

        AgentRunCommand failed = commandStore.load("bad");
        assertEquals(AgentRunCommandStatus.FAILED, failed.getStatus());
        assertEquals(3, failed.getAttempts());
        assertNotNull(failed.getErrorMessage());
    }

    @Test
    public void shouldNotifyWakeupOnceAndIgnoreListenerFailure() {
        AtomicInteger successfulListener = new AtomicInteger();
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("wakeup").chatModel(model).build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent))
            .addWakeupListener(command -> { throw new RuntimeException("unavailable"); })
            .addWakeupListener(command -> successfulListener.incrementAndGet());
        AgentRun waiting = runner.start(agent, "input");
        runner.suspend(waiting, AgentSuspension.userInput("value"));

        runner.submitCommand("wake-1", waiting.getId(), AgentResumeCommand.userInput("answer"));
        runner.submitCommand("wake-1", waiting.getId(), AgentResumeCommand.userInput("answer"));

        assertEquals(1, successfulListener.get());
    }

    private AgentRunCommand command(String id) {
        return AgentRunCommand.pending(id, "run", AgentResumeCommand.continueRun());
    }

    private InMemoryAgentRunCommandStore claimedStore(String id, String worker) {
        InMemoryAgentRunCommandStore store = new InMemoryAgentRunCommandStore();
        store.submit(command(id));
        store.claim(worker, 100, 20, 1);
        return store;
    }

    private void assertIllegalState(Runnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private void submit(InMemoryAgentRunCommandStore store, String runId,
                        CountDownLatch start, AtomicInteger accepted,
                        AtomicInteger rejected) {
        try {
            start.await();
            store.submit(AgentRunCommand.pending("same-id", runId,
                AgentResumeCommand.continueRun()));
            accepted.incrementAndGet();
        } catch (IllegalArgumentException expected) {
            rejected.incrementAndGet();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}

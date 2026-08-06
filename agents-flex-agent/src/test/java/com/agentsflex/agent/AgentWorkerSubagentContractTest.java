/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Worker Lease fencing、子 Agent 失败和取消竞态测试。 */
public class AgentWorkerSubagentContractTest {

    @Test
    public void shouldFenceStaleWorkerAfterLeaseIsReclaimed() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        Agent agent = Agent.builder("lease-fencing")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentTurn turn = runner.start(agent, "input");
        AgentTurnSnapshot workerA = store.claimRunnable("worker-a", 100, 10, 1).get(0);
        AgentTurn stale = runner.restore(workerA.getState().getTurnId());
        AgentTurnSnapshot workerB = store.claimRunnable("worker-b", 110, 10, 1).get(0);

        assertEquals("worker-b", workerB.getState().getLeaseOwner());
        try {
            store.save(stale.toSnapshot(), stale.getVersion());
            fail("stale worker snapshot must be fenced");
        } catch (AgentTurnVersionConflictException expected) {
            assertTrue(expected.getMessage().contains(
                "expectedVersion=" + workerA.getState().getVersion()));
            assertTrue(expected.getMessage().contains(
                "actualVersion=" + workerB.getState().getVersion()));
        }
    }

    @Test
    public void shouldAllowOnlyOwnerToRenewLease() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        AgentTurn turn = runner.start(Agent.builder("lease-renew")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build(), "input");
        AgentTurnSnapshot claimed = store.claimRunnable("worker-a", 100, 20, 1).get(0);

        AgentTurnSnapshot renewed = store.renewLease(turn.getId(), "worker-a",
            claimed.getState().getLeaseId(), 110, 200);

        assertEquals(200, renewed.getState().getLeaseUntil());
        assertEquals(claimed.getState().getVersion(), renewed.getState().getVersion());
        try {
            store.renewLease(turn.getId(), "worker-b",
                claimed.getState().getLeaseId(), 120, 300);
            fail("non-owner must not renew lease");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("worker-b"));
        }
    }

    @Test
    public void shouldFenceOldLeaseTokenEvenWhenWorkerIdIsReused() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        Agent agent = Agent.builder("lease-token")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentTurn turn = runner.start(agent, "input");
        AgentTurnSnapshot oldLease = store.claimRunnable("shared-worker", 100, 10, 1).get(0);
        AgentTurnSnapshot newLease = store.claimRunnable("shared-worker", 110, 100, 1).get(0);

        store.releaseLease(turn.getId(), "shared-worker", oldLease.getState().getLeaseId());

        AgentTurnSnapshot current = store.load(turn.getId());
        assertEquals(newLease.getState().getLeaseId(), current.getState().getLeaseId());
        assertEquals(210, current.getState().getLeaseUntil());
        try {
            store.renewLease(turn.getId(), "shared-worker",
                oldLease.getState().getLeaseId(), 120, 220);
            fail("stale lease token must not renew a newer lease");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("shared-worker"));
        }
    }

    @Test
    public void shouldKeepLeaseAliveDuringLongModelCall() throws Exception {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        CountDownLatch modelStarted = new CountDownLatch(1);
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            modelStarted.countDown();
            try {
                Thread.sleep(180);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(error);
            }
            return new AiMessage("completed");
        });
        Agent agent = Agent.builder("heartbeat-agent").chatModel(model).build();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        runner.start(agent, "input");
        AgentWorker worker = new AgentWorker("worker-a", runner, 60);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<AgentTurn>> execution = executor.submit(() -> worker.pollAndRun(1));
            assertTrue(modelStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);

            assertTrue(store.claimRunnable("worker-b", System.currentTimeMillis(), 60, 1).isEmpty());
            // 关闭只停止新轮询，正在执行的 Turn 会继续续租到本次 poll 退出。
            worker.close();
            assertEquals(AgentTurnStatus.COMPLETED,
                execution.get(2, TimeUnit.SECONDS).get(0).getStatus());
        } finally {
            worker.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldRecoverParentWakeupAfterChildCompletionCrashWindow() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentScenarioTestSupport.QueueChatModel parentModel =
            new AgentScenarioTestSupport.QueueChatModel();
        parentModel.enqueue(prompt -> new AiMessage("parent completed"));
        AgentScenarioTestSupport.QueueChatModel childModel =
            new AgentScenarioTestSupport.QueueChatModel();
        childModel.enqueue(prompt -> new AiMessage("child completed"));
        Agent parentAgent = Agent.builder("recovery-parent").chatModel(parentModel).build();
        Agent childAgent = Agent.builder("recovery-child").chatModel(childModel).build();
        AgentRunner runner = new AgentRunner(store,
            new InMemoryAgentLoader(parentAgent, childAgent));
        AgentTurn parent = runner.start(parentAgent, "parent input");
        AgentTurn child = runner.startChild(parent, childAgent.getId(), "child input");
        child = runner.runUntilBlocked(child);
        assertEquals(AgentTurnStatus.COMPLETED, child.getStatus());
        assertEquals(AgentTurnStatus.WAITING_FOR_CHILD,
            runner.restore(parent.getId()).getStatus());

        AgentWorker worker = new AgentWorker("recovery-worker", runner, 1000);
        try {
            List<AgentTurn> completed = worker.pollAndRun(1);
            assertEquals(1, completed.size());
            assertEquals(AgentTurnStatus.COMPLETED, completed.get(0).getStatus());
            assertEquals("parent completed", completed.get(0).getFinalOutput());
        } finally {
            worker.close();
        }
    }

    @Test
    public void shouldResumeParentWithFailedChildResult() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentScenarioTestSupport.QueueChatModel parentModel =
            new AgentScenarioTestSupport.QueueChatModel();
        parentModel.enqueue(prompt -> {
            assertTrue(prompt.getMessages().stream()
                .anyMatch(message -> message.getTextContent().contains("child failed")));
            return new AiMessage("parent recovered");
        });
        AgentScenarioTestSupport.QueueChatModel childModel =
            new AgentScenarioTestSupport.QueueChatModel();
        childModel.enqueue(prompt -> { throw new RuntimeException("child failed"); });
        Agent parentAgent = Agent.builder("failed-child-parent")
            .chatModel(parentModel).build();
        Agent childAgent = Agent.builder("failed-child")
            .chatModel(childModel).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(parentAgent, childAgent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentTurn parent = runner.start(parentAgent, "parent input");
        AgentTurn child = runner.startChild(parent, childAgent.getId(), "child input");

        child = runner.runUntilBlocked(child);
        AgentTurn resumed = runner.resumeParentFromChild(child);
        AgentTurn completed = runner.runUntilBlocked(resumed);

        assertEquals(AgentTurnStatus.FAILED, child.getStatus());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("parent recovered", completed.getFinalOutput());
    }

    @Test
    public void shouldNotResumeCancelledParentFromLateChildCompletion() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentScenarioTestSupport.QueueChatModel childModel =
            new AgentScenarioTestSupport.QueueChatModel();
        childModel.enqueue(prompt -> new AiMessage("late result"));
        Agent parentAgent = Agent.builder("cancelled-parent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        Agent childAgent = Agent.builder("late-child").chatModel(childModel).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(parentAgent, childAgent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentTurn parent = runner.start(parentAgent, "parent input");
        AgentTurn child = runner.startChild(parent, childAgent.getId(), "child input");
        runner.requestCancellation(parent.getId());
        AgentTurn cancelledParent = runner.runUntilBlocked(parent.getId());
        child = runner.runUntilBlocked(child);

        AgentTurn result = runner.resumeParentFromChild(child);

        assertEquals(AgentTurnStatus.CANCELLED, cancelledParent.getStatus());
        assertEquals(AgentTurnStatus.CANCELLED, result.getStatus());
        assertFalse(result.getPrompt().getMemory().getMessages(Integer.MAX_VALUE).stream()
            .anyMatch(message -> message.getTextContent().contains("late result")));
    }

    @Test
    public void shouldPreserveStreamingOptionForChildAndSnapshotRestore() {
        Agent parentAgent = Agent.builder("context-parent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        Agent childAgent = Agent.builder("context-child")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(parentAgent, childAgent);
        AgentRunner runner = new AgentRunner(new InMemoryAgentTurnStore(), registry);
        AgentTurn parent = runner.start(parentAgent, "input",
            AgentTurnOptions.builder().streaming(true).build());

        AgentTurn child = runner.startChild(parent, childAgent.getId(), "child input");

        assertTrue(child.isStreaming());
        assertTrue(runner.restore(child.getId()).isStreaming());
    }

    @Test
    public void shouldCloseWorkerPollingIdempotently() {
        AgentWorker worker = new AgentWorker("close-worker", new AgentRunner(), 1000);
        worker.startPolling(1000, 1);
        assertTrue(worker.isPolling());

        worker.close();
        worker.close();

        assertFalse(worker.isPolling());
    }
}

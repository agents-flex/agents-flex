/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentRunVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
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
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Agent agent = Agent.builder("lease-fencing")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentRun run = runner.start(agent, "input");
        AgentRunSnapshot workerA = store.claimRunnable("worker-a", 100, 10, 1).get(0);
        AgentRun stale = runner.restore(workerA.getRunId());
        AgentRunSnapshot workerB = store.claimRunnable("worker-b", 110, 10, 1).get(0);

        assertEquals("worker-b", workerB.getLeaseOwner());
        try {
            store.save(stale.toSnapshot(), stale.getVersion());
            fail("stale worker snapshot must be fenced");
        } catch (AgentRunVersionConflictException expected) {
            assertTrue(expected.getMessage().contains(
                "expectedVersion=" + workerA.getVersion()));
            assertTrue(expected.getMessage().contains(
                "actualVersion=" + workerB.getVersion()));
        }
    }

    @Test
    public void shouldAllowOnlyOwnerToRenewLease() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        AgentRun run = runner.start(Agent.builder("lease-renew")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build(), "input");
        AgentRunSnapshot claimed = store.claimRunnable("worker-a", 100, 20, 1).get(0);

        AgentRunSnapshot renewed = store.renewLease(run.getId(), "worker-a",
            claimed.getLeaseId(), 110, 200);

        assertEquals(200, renewed.getLeaseUntil());
        assertEquals(claimed.getVersion(), renewed.getVersion());
        try {
            store.renewLease(run.getId(), "worker-b", claimed.getLeaseId(), 120, 300);
            fail("non-owner must not renew lease");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("worker-b"));
        }
    }

    @Test
    public void shouldFenceOldLeaseTokenEvenWhenWorkerIdIsReused() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Agent agent = Agent.builder("lease-token")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentRun run = runner.start(agent, "input");
        AgentRunSnapshot oldLease = store.claimRunnable("shared-worker", 100, 10, 1).get(0);
        AgentRunSnapshot newLease = store.claimRunnable("shared-worker", 110, 100, 1).get(0);

        store.releaseLease(run.getId(), "shared-worker", oldLease.getLeaseId());

        AgentRunSnapshot current = store.load(run.getId());
        assertEquals(newLease.getLeaseId(), current.getLeaseId());
        assertEquals(210, current.getLeaseUntil());
        try {
            store.renewLease(run.getId(), "shared-worker", oldLease.getLeaseId(), 120, 220);
            fail("stale lease token must not renew a newer lease");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("shared-worker"));
        }
    }

    @Test
    public void shouldKeepLeaseAliveDuringLongModelCall() throws Exception {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
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
            Future<List<AgentRun>> execution = executor.submit(() -> worker.pollAndRun(1));
            assertTrue(modelStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);

            assertTrue(store.claimRunnable("worker-b", System.currentTimeMillis(), 60, 1).isEmpty());
            // 关闭只停止新轮询，正在执行的 Run 会继续续租到本次 poll 退出。
            worker.close();
            assertEquals(AgentRunStatus.COMPLETED,
                execution.get(2, TimeUnit.SECONDS).get(0).getStatus());
        } finally {
            worker.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldRecoverParentWakeupAfterChildCompletionCrashWindow() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
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
        AgentRun parent = runner.start(parentAgent, "parent input");
        AgentRun child = runner.startChild(parent, childAgent.getId(), "child input");
        child = runner.runUntilBlocked(child);
        assertEquals(AgentRunStatus.COMPLETED, child.getStatus());
        assertEquals(AgentRunStatus.WAITING_FOR_CHILD,
            runner.restore(parent.getId()).getStatus());

        AgentWorker worker = new AgentWorker("recovery-worker", runner, 1000);
        try {
            List<AgentRun> completed = worker.pollAndRun(1);
            assertEquals(1, completed.size());
            assertEquals(AgentRunStatus.COMPLETED, completed.get(0).getStatus());
            assertEquals("parent completed", completed.get(0).getFinalOutput());
        } finally {
            worker.close();
        }
    }

    @Test
    public void shouldResumeParentWithFailedChildResult() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
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
        AgentRun parent = runner.start(parentAgent, "parent input");
        AgentRun child = runner.startChild(parent, childAgent.getId(), "child input");

        child = runner.runUntilBlocked(child);
        AgentRun resumed = runner.resumeParentFromChild(child);
        AgentRun completed = runner.runUntilBlocked(resumed);

        assertEquals(AgentRunStatus.FAILED, child.getStatus());
        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("parent recovered", completed.getFinalOutput());
    }

    @Test
    public void shouldNotResumeCancelledParentFromLateChildCompletion() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentScenarioTestSupport.QueueChatModel childModel =
            new AgentScenarioTestSupport.QueueChatModel();
        childModel.enqueue(prompt -> new AiMessage("late result"));
        Agent parentAgent = Agent.builder("cancelled-parent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        Agent childAgent = Agent.builder("late-child").chatModel(childModel).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(parentAgent, childAgent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentRun parent = runner.start(parentAgent, "parent input");
        AgentRun child = runner.startChild(parent, childAgent.getId(), "child input");
        runner.requestCancellation(parent.getId());
        AgentRun cancelledParent = runner.runUntilBlocked(parent.getId());
        child = runner.runUntilBlocked(child);

        AgentRun result = runner.resumeParentFromChild(child);

        assertEquals(AgentRunStatus.CANCELLED, cancelledParent.getStatus());
        assertEquals(AgentRunStatus.CANCELLED, result.getStatus());
        assertFalse(result.getPrompt().getMemory().getMessages(Integer.MAX_VALUE).stream()
            .anyMatch(message -> message.getTextContent().contains("late result")));
    }

    @Test
    public void shouldInheritStreamingOptionWhenCreatingChildInSameProcess() {
        Agent parentAgent = Agent.builder("context-parent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        Agent childAgent = Agent.builder("context-child")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(parentAgent, childAgent);
        AgentRunner runner = new AgentRunner(new InMemoryAgentRunStore(), registry);
        AgentRun parent = runner.start(parentAgent, "input",
            AgentRunOptions.builder().streaming(true).build());

        AgentRun child = runner.startChild(parent, childAgent.getId(), "child input");

        assertTrue(child.isStreaming());
        assertFalse(runner.restore(child.getId()).isStreaming());
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

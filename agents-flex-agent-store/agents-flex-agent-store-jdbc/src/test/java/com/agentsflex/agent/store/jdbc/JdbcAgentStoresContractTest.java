package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentResumeCommand;
import com.agentsflex.agent.AgentRunSnapshot;
import com.agentsflex.agent.AgentRunStatus;
import com.agentsflex.agent.command.AgentRunCommand;
import com.agentsflex.agent.command.AgentRunCommandStatus;
import com.agentsflex.agent.context.AgentArtifactReference;
import com.agentsflex.agent.store.AgentRunVersionConflictException;
import com.agentsflex.agent.store.ParentChildRunSnapshots;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/** 验证 JDBC 实现与 Agent Store SPI 的状态、并发和恢复语义一致。 */
public class JdbcAgentStoresContractTest {
    private JdbcAgentStoreConfig config;

    @Before
    public void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config = JdbcAgentStoreConfig.builder(dataSource).tablePrefix("test_agent_").build();
        config.schema().initialize();
    }

    @Test
    public void shouldPersistRunWithOptimisticLockCancellationAndLease() {
        JdbcAgentRunStore store = config.runStore();
        AgentRunSnapshot created = store.save(snapshot("run-1", AgentRunStatus.READY), -1);
        assertEquals(0, created.getVersion());
        assertEquals("value", store.load("run-1").getMetadata().get("key"));

        assertTrue(store.requestCancellation("run-1"));
        assertFalse(store.requestCancellation("run-1"));
        AgentRunSnapshot updated = store.save(created.toBuilder().status(AgentRunStatus.RUNNING).build(), 0);
        assertTrue(updated.isCancellationRequested());

        List<AgentRunSnapshot> claimed = store.claimRunnable("worker-a", 1000, 5000, 10);
        assertEquals(1, claimed.size());
        assertEquals("worker-a", claimed.get(0).getLeaseOwner());
        long claimedVersion = claimed.get(0).getVersion();
        AgentRunSnapshot renewed = store.renewLease("run-1", "worker-a", claimed.get(0).getLeaseId(),
            1500, 7000);
        assertEquals(7000, renewed.getLeaseUntil());
        assertEquals(claimedVersion, renewed.getVersion());
        try {
            store.renewLease("run-1", "worker-a", "stale-token", 1600, 8000);
            fail("stale lease token must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("worker-a"));
        }
        store.releaseLease("run-1", "worker-a", "stale-token");
        assertEquals(claimed.get(0).getLeaseId(), store.load("run-1").getLeaseId());
        store.releaseLease("run-1", "worker-a", claimed.get(0).getLeaseId());
        assertNull(store.load("run-1").getLeaseOwner());
        assertEquals(claimedVersion, store.load("run-1").getVersion());

        try {
            store.save(created, 0);
            fail("Expected optimistic lock conflict");
        } catch (AgentRunVersionConflictException expected) {
            assertTrue(expected.getMessage().contains("run-1"));
        }
    }

    @Test
    public void shouldAtomicallyPersistParentAndChildAndDelayChildWhileParentLeased() {
        JdbcAgentRunStore store = config.runStore();
        AgentRunSnapshot parent = store.save(snapshot("parent", AgentRunStatus.RUNNING), -1);
        AgentRunSnapshot child = snapshot("child", AgentRunStatus.READY).toBuilder().parentRunId("parent").rootRunId("parent").build();
        ParentChildRunSnapshots pair = store.saveParentAndChild(
            parent.toBuilder().status(AgentRunStatus.WAITING_FOR_CHILD).build(), 0, child);
        assertEquals(1, pair.getParent().getVersion());
        assertEquals(0, pair.getChild().getVersion());
        assertEquals("parent", store.load("child").getParentRunId());
    }

    @Test
    public void shouldProvideIdempotentLeasedCommandInbox() {
        JdbcAgentRunCommandStore store = config.commandStore();
        AgentRunCommand command = AgentRunCommand.pending("command-1", "run-1", AgentResumeCommand.userInput("answer"));
        assertEquals(command.getCommandId(), store.submit(command).getCommandId());
        assertEquals(command.getCommandId(), store.submit(command).getCommandId());

        AgentRunCommand claimed = store.claim("worker-a", 100, 50, 1).get(0);
        assertEquals(AgentRunCommandStatus.CLAIMED, claimed.getStatus());
        assertEquals(1, claimed.getAttempts());
        store.release("command-1", "worker-a", "temporary");
        assertEquals(AgentRunCommandStatus.PENDING, store.load("command-1").getStatus());
        AgentRunCommand retried = store.claim("worker-b", 200, 50, 1).get(0);
        assertEquals(2, retried.getAttempts());
        store.acknowledge("command-1", "worker-b");
        assertEquals(AgentRunCommandStatus.COMPLETED, store.load("command-1").getStatus());
    }

    @Test
    public void shouldPersistUtf8ArtifactAndChecksum() {
        JdbcAgentArtifactStore store = config.artifactStore();
        AgentArtifactReference reference = store.save("run-1", "text/plain", "大型工具结果",
            Collections.singletonMap("source", "tool"));
        assertEquals("大型工具结果", store.load(reference.getArtifactId()));
        assertEquals("tool", reference.getMetadata().get("source"));
        assertEquals(64, reference.getChecksum().length());
        assertTrue(reference.getSize() > 6);
    }

    @Test
    public void shouldAllowOnlyOneWorkerToClaimTheSameRun() throws Exception {
        final JdbcAgentRunStore store = config.runStore();
        store.save(snapshot("race-run", AgentRunStatus.READY), -1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<AgentRunSnapshot>> first = executor.submit(() -> {
                start.await(); return store.claimRunnable("worker-a", 100, 1000, 1);
            });
            Future<List<AgentRunSnapshot>> second = executor.submit(() -> {
                start.await(); return store.claimRunnable("worker-b", 100, 1000, 1);
            });
            start.countDown();
            assertEquals(1, first.get().size() + second.get().size());
        } finally { executor.shutdownNow(); }
    }

    private AgentRunSnapshot snapshot(String runId, AgentRunStatus status) {
        return AgentRunSnapshot.builder(runId, "agent", "1")
            .executionPolicy(AgentExecutionPolicy.defaults())
            .status(status).createdAt(1).updatedAt(1).rootRunId(runId)
            .metadata(Collections.<String, Object>singletonMap("key", "value")).build();
    }

}

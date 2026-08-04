package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentResumeCommand;
import com.agentsflex.agent.AgentRunSnapshot;
import com.agentsflex.agent.AgentRunStatus;
import com.agentsflex.agent.command.AgentRunCommand;
import com.agentsflex.agent.command.AgentRunCommandStatus;
import com.agentsflex.agent.context.AgentArtifactReference;
import com.agentsflex.agent.store.ParentChildRunSnapshots;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/** 在真实 Redis 上验证 Lua 原子操作和五类 Agent Store 的组合行为。 */
public class RedisAgentStoresIntegrationTest {
    private RedisAgentStoreConfig config;
    private String prefix;

    @Before
    public void setUp() {
        prefix = "agents-flex-agent-it:" + UUID.randomUUID() + ":";
        config = RedisAgentStoreConfig.builder(System.getProperty("redis.test.uri", "redis://127.0.0.1:6379"))
            .keyPrefix(prefix).build();
        try { config.jedis().ping(); }
        catch (RuntimeException error) {
            config.close(); config = null;
            Assume.assumeNoException("Redis is required for Agent Store integration tests", error);
        }
    }

    @After
    public void tearDown() {
        if (config == null) return;
        String cursor = "0";
        do {
            ScanResult<String> scan = config.jedis().scan(cursor, new ScanParams().match(prefix + "*").count(100));
            cursor = scan.getCursor();
            if (!scan.getResult().isEmpty()) config.jedis().del(scan.getResult().toArray(new String[0]));
        } while (!"0".equals(cursor));
        config.close();
    }

    @Test
    public void shouldPersistAndCoordinateAllAgentStateTypes() {
        RedisAgentRunStore runs = config.runStore();
        AgentRunSnapshot run = runs.save(snapshot(), -1);
        assertEquals(0, run.getVersion());
        assertTrue(runs.requestCancellation("run-1"));
        assertTrue(runs.load("run-1").isCancellationRequested());
        AgentRunSnapshot claimedRun = runs.claimRunnable("worker", 100, 1000, 1).get(0);
        assertEquals("worker", claimedRun.getLeaseOwner());
        assertEquals(2000, runs.renewLease("run-1", "worker", claimedRun.getLeaseId(),
            200, 2000).getLeaseUntil());
        runs.releaseLease("run-1", "worker", claimedRun.getLeaseId());

        RedisAgentRunCommandStore commands = config.commandStore();
        AgentRunCommand command = AgentRunCommand.pending("command-1", "run-1", AgentResumeCommand.continueRun());
        commands.submit(command); commands.submit(command);
        AgentRunCommand claimedCommand = commands.claim("worker", 100, 1000, 1).get(0);
        assertEquals(1, claimedCommand.getAttempts());
        commands.acknowledge("command-1", "worker");
        assertEquals(AgentRunCommandStatus.COMPLETED, commands.load("command-1").getStatus());

        RedisAgentArtifactStore artifacts = config.artifactStore();
        AgentArtifactReference artifact = artifacts.save("run-1", "text/plain", "大型结果", null);
        assertEquals("大型结果", artifacts.load(artifact.getArtifactId()));
    }

    @Test
    public void shouldCreateParentAndChildAtomically() {
        RedisAgentRunStore runs = config.runStore();
        AgentRunSnapshot parent = runs.save(snapshot("parent", AgentRunStatus.RUNNING), -1);
        AgentRunSnapshot child = snapshot("child", AgentRunStatus.READY).toBuilder()
            .parentRunId("parent").rootRunId("parent").build();
        ParentChildRunSnapshots pair = runs.saveParentAndChild(parent.toBuilder()
            .status(AgentRunStatus.WAITING_FOR_CHILD).build(), 0, child);
        assertEquals(1, pair.getParent().getVersion());
        assertEquals(0, pair.getChild().getVersion());
        assertEquals("parent", runs.load("child").getParentRunId());
    }

    @Test
    public void shouldAllowOnlyOneWorkerToClaimRun() throws Exception {
        final RedisAgentRunStore runs = config.runStore();
        runs.save(snapshot("race", AgentRunStatus.READY), -1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<AgentRunSnapshot>>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> { start.await(); return runs.claimRunnable("worker-a", 100, 1000, 1); }));
            futures.add(executor.submit(() -> { start.await(); return runs.claimRunnable("worker-b", 100, 1000, 1); }));
            start.countDown();
            assertEquals(1, futures.get(0).get().size() + futures.get(1).get().size());
        } finally { executor.shutdownNow(); }
    }

    private AgentRunSnapshot snapshot() {
        return snapshot("run-1", AgentRunStatus.READY);
    }

    private AgentRunSnapshot snapshot(String runId, AgentRunStatus status) {
        return AgentRunSnapshot.builder(runId, "agent", "1")
            .executionPolicy(AgentExecutionPolicy.defaults())
            .status(status).rootRunId(runId).createdAt(1).updatedAt(1).build();
    }

}

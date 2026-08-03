/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentRunStore;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 按指定版本 Agent 恢复待执行工具的组合场景测试。 */
public class AgentToolResolutionTest {

    @Test
    public void shouldExposeImmutableToolMetadataAndResolveToolFromAgent() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("provider", "mcp");
        Tool tool = Tool.builder("query_order")
            .metadata(source)
            .function(arguments -> "ok")
            .build();
        Agent agent = Agent.builder("order-agent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .tool(tool)
            .build();

        source.put("provider", "changed");

        assertEquals("mcp", tool.getMetadata().get("provider"));
        assertSame(tool, agent.getTool("query_order"));
        assertNull(agent.getTool("unknown"));
        try {
            tool.getMetadata().put("new", "value");
            fail("Tool metadata must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Tool 只暴露只读元数据。
        }
    }

    @Test
    public void shouldRestorePendingToolFromOriginalAgentVersion() {
        InMemoryAgentRunStore durableStore = new InMemoryAgentRunStore();
        CrashAfterPendingSnapshotStore crashingStore =
            new CrashAfterPendingSnapshotStore(durableStore);
        AgentScenarioTestSupport.QueueChatModel versionOneModel =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger versionOneExecutions = new AtomicInteger();
        AtomicInteger versionTwoExecutions = new AtomicInteger();
        versionOneModel.enqueue(prompt -> toolCalls(
            new ToolCall("call-1", "query_order", "{}")));
        versionOneModel.enqueue(prompt -> new AiMessage("finished with version one"));

        Agent versionOne = Agent.builder("order-agent")
            .id("order-agent")
            .version("1")
            .chatModel(versionOneModel)
            .tool(AgentScenarioTestSupport.tool("query_order",
                arguments -> versionOneExecutions.incrementAndGet()))
            .build();
        Agent versionTwo = Agent.builder("order-agent")
            .id("order-agent")
            .version("2")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .tool(AgentScenarioTestSupport.tool("query_order",
                arguments -> versionTwoExecutions.incrementAndGet()))
            .build();
        InMemoryAgentLoader agentLoader = new InMemoryAgentLoader(versionOne, versionTwo);
        assertSame(versionTwo, agentLoader.loadActive("order-agent"));

        AgentRunner firstRunner = new AgentRunner(crashingStore, agentLoader);
        AgentRun run = firstRunner.start(versionOne, "query order");
        try {
            firstRunner.step(run);
            fail("Expected simulated process crash");
        } catch (SimulatedProcessCrash expected) {
            // ToolCall 已经保存，工具尚未执行。
        }

        AgentRunSnapshot snapshot = durableStore.load(run.getId());
        assertEquals("1", snapshot.getAgentVersion());
        assertEquals("query_order", snapshot.getPendingToolCalls().get(0).getName());

        AgentRunner secondRunner = new AgentRunner(durableStore, agentLoader);
        AgentRun completed = secondRunner.runUntilBlocked(run.getId());

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("finished with version one", completed.getFinalOutput());
        assertEquals(1, versionOneExecutions.get());
        assertEquals(0, versionTwoExecutions.get());
    }

    @Test
    public void shouldFailWhenOriginalAgentVersionDoesNotContainPendingTool() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Agent agent = Agent.builder("strict-tool-agent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .build();
        ToolCall call = new ToolCall("missing-call", "write_order", "{}");
        AgentRunSnapshot invalid = AgentRunSnapshot.builder(
                "missing-tool-run", agent.getId(), agent.getVersion())
            .executionPolicy(agent.getExecutionPolicy())
            .status(AgentRunStatus.RUNNING)
            .phase(AgentRunPhase.TOOLS)
            .messages(Collections.emptyList())
            .pendingToolCalls(Collections.singletonList(call))
            .createdAt(System.currentTimeMillis())
            .build();
        store.save(invalid, -1);

        AgentRun failed = new AgentRunner(store, new InMemoryAgentLoader(agent))
            .runUntilBlocked(invalid.getRunId());

        assertEquals(AgentRunStatus.FAILED, failed.getStatus());
        assertTrue(failed.getError().getMessage().contains("tool not found: write_order"));
    }

    private static final class CrashAfterPendingSnapshotStore implements AgentRunStore {
        private final AgentRunStore delegate;
        private boolean crashed;

        private CrashAfterPendingSnapshotStore(AgentRunStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AgentRunSnapshot load(String runId) { return delegate.load(runId); }

        @Override
        public boolean requestCancellation(String runId) {
            return delegate.requestCancellation(runId);
        }

        @Override
        public boolean isCancellationRequested(String runId) {
            return delegate.isCancellationRequested(runId);
        }

        @Override
        public AgentRunSnapshot save(AgentRunSnapshot snapshot, long expectedVersion) {
            AgentRunSnapshot saved = delegate.save(snapshot, expectedVersion);
            if (!crashed && AgentRunPhase.TOOLS.equals(saved.getPhase())) {
                crashed = true;
                throw new SimulatedProcessCrash();
            }
            return saved;
        }
    }

    private static final class SimulatedProcessCrash extends RuntimeException {
    }
}

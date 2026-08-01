/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.registry.InMemoryAgentRegistry;
import com.agentsflex.core.agent.store.AgentRunStore;
import com.agentsflex.core.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.agent.tool.AgentToolReference;
import com.agentsflex.core.agent.tool.AgentToolRegistry;
import com.agentsflex.core.agent.tool.InMemoryAgentToolRegistry;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.core.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tool metadata、持久化引用和跨 Runner 解析的组合场景测试。 */
public class AgentToolReferenceTest {

    @Test
    public void shouldBuildImmutableToolMetadataAndReference() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("provider", "mcp");
        Tool tool = Tool.builder("query_order")
            .metadata(source)
            .metadata("riskLevel", "LOW")
            .function(arguments -> "ok")
            .build();

        source.put("provider", "changed");
        assertEquals("mcp", tool.getMetadata().get("provider"));
        try {
            tool.getMetadata().put("new", "value");
            fail("Tool metadata must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Tool 只暴露只读元数据。
        }

        AgentToolReference reference = AgentToolReference.builder(
                "order-agent", "3", tool.getName())
            .bindingId("order-mcp/query_order")
            .bindingVersion("2")
            .metadata(tool.getMetadata())
            .build();

        assertEquals("order-agent", reference.getAgentId());
        assertEquals("3", reference.getAgentVersion());
        assertEquals("order-mcp/query_order", reference.getBindingId());
        assertEquals("2", reference.getBindingVersion());
        assertEquals("mcp", reference.getMetadata().get("provider"));
        assertNotSame(tool.getMetadata(), reference.getMetadata());
        try {
            reference.getMetadata().put("provider", "changed");
            fail("Reference metadata must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Reference 保存创建时冻结的元数据。
        }

        InMemoryAgentToolRegistry registry = new InMemoryAgentToolRegistry();
        AgentToolReference first = registry.register("order-agent", "3", tool);
        AgentToolReference second = registry.register("order-agent", "3", tool);
        assertEquals(first, second);
        assertSame(tool, registry.resolve(first));
    }

    @Test
    public void shouldPersistReferenceAndResolveToolInAnotherRunner() {
        InMemoryAgentRunStore durableStore = new InMemoryAgentRunStore();
        CrashAfterPendingCheckpointStore crashingStore =
            new CrashAfterPendingCheckpointStore(durableStore);
        InMemoryAgentRegistry agentRegistry = new InMemoryAgentRegistry();
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(
            new ToolCall("call-1", "query_order", "{\"orderNo\":\"A1001\"}")));
        model.enqueue(prompt -> new AiMessage("finished"));

        Tool configuredTool = Tool.builder("query_order")
            .metadata("provider", "mcp")
            .metadata("serverId", "order-mcp")
            .function(arguments -> "configured instance")
            .build();
        Agent agent = Agent.builder("order-agent")
            .id("order-agent")
            .version("3")
            .chatModel(model)
            .tool(configuredTool)
            .build();

        BindingToolRegistry firstRegistry = new BindingToolRegistry(executions);
        AgentRunner firstRunner = new AgentRunner(
            crashingStore, agentRegistry, firstRegistry);
        AgentRun run = firstRunner.start(agent, "query order");
        try {
            firstRunner.step(run);
            fail("Expected simulated process crash");
        } catch (SimulatedProcessCrash expected) {
            // pending ToolCall 和 Reference 已经写入共享 Store。
        }

        AgentRunSnapshot checkpoint = durableStore.load(run.getId());
        AgentToolReference stored = checkpoint.getPendingToolReferences().get("call-1");
        assertEquals("order-mcp/query_order", stored.getBindingId());
        assertEquals("7", stored.getBindingVersion());
        assertEquals("mcp", stored.getMetadata().get("provider"));
        assertEquals("order-mcp", stored.getMetadata().get("serverId"));

        BindingToolRegistry secondRegistry = new BindingToolRegistry(executions);
        AgentRunner secondRunner = new AgentRunner(
            durableStore, agentRegistry, secondRegistry);
        AgentRun completed = secondRunner.runUntilBlocked(run.getId());

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("finished", completed.getFinalOutput());
        assertEquals(1, executions.get());
        assertEquals(stored, secondRegistry.getResolvedReference());
        assertTrue(completed.getPendingToolReferences().isEmpty());
    }

    @Test
    public void shouldFailWhenPendingToolCallHasNoReference() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryAgentRegistry agentRegistry = new InMemoryAgentRegistry();
        AtomicInteger executions = new AtomicInteger();
        Agent agent = Agent.builder("strict-reference-agent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .tool(AgentScenarioTestSupport.tool("write_order",
                arguments -> executions.incrementAndGet()))
            .build();
        agentRegistry.register(agent);

        ToolCall call = new ToolCall("call-without-reference", "write_order", "{}");
        AgentRunSnapshot invalid = AgentRunSnapshot.builder(
                "invalid-reference-run", agent.getId(), agent.getVersion())
            .executionMode(agent.getExecutionMode().getId(), agent.getExecutionMode().getVersion())
            .executionPolicy(agent.getExecutionPolicy())
            .status(AgentRunStatus.RUNNING)
            .phase(AgentRunPhase.TOOLS)
            .messages(Collections.emptyList())
            .pendingToolCalls(Collections.singletonList(call))
            .createdAt(System.currentTimeMillis())
            .build();
        store.save(invalid, -1);

        AgentRunner runner = new AgentRunner(store, agentRegistry);
        AgentRun failed = runner.runUntilBlocked(invalid.getRunId());

        assertEquals(AgentRunStatus.FAILED, failed.getStatus());
        assertEquals(0, executions.get());
        assertTrue(failed.getError().getMessage().contains("tool not found: write_order"));
    }

    /** 使用 metadata 生成绑定引用，并在新进程中按引用构造新的工具对象。 */
    private static final class BindingToolRegistry implements AgentToolRegistry {
        private final AtomicInteger executions;
        private AgentToolReference resolvedReference;

        private BindingToolRegistry(AtomicInteger executions) {
            this.executions = executions;
        }

        @Override
        public AgentToolReference register(String agentId, String agentVersion, Tool tool) {
            AgentToolReference reference = AgentToolReference.builder(
                    agentId, agentVersion, tool.getName())
                .bindingId(String.valueOf(tool.getMetadata().get("serverId"))
                    + "/" + tool.getName())
                .bindingVersion("7")
                .metadata(tool.getMetadata())
                .build();
            // 测试模拟只保存引用、不共享 Tool Java 对象的外部注册表。
            return reference;
        }

        @Override
        public Tool resolve(AgentToolReference reference) {
            this.resolvedReference = reference;
            if (!"order-mcp/query_order".equals(reference.getBindingId())
                || !"7".equals(reference.getBindingVersion())
                || !"mcp".equals(reference.getMetadata().get("provider"))) {
                return null;
            }
            return AgentScenarioTestSupport.tool(reference.getToolName(),
                arguments -> executions.incrementAndGet());
        }

        private AgentToolReference getResolvedReference() {
            return resolvedReference;
        }
    }

    private static final class CrashAfterPendingCheckpointStore implements AgentRunStore {
        private final AgentRunStore delegate;
        private boolean crashed;

        private CrashAfterPendingCheckpointStore(AgentRunStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AgentRunSnapshot load(String runId) {
            return delegate.load(runId);
        }

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

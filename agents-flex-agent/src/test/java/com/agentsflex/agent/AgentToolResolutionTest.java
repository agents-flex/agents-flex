/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.store.AgentTurnStore;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.AgentToolResolver;
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

/**
 * 按指定版本 Agent 恢复待执行工具的组合场景测试。
 */
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
    public void shouldAutomaticallyUseToolResolverDeclaredByMiddleware() {
        AtomicInteger executions = new AtomicInteger();
        Tool dynamic = AgentScenarioTestSupport.tool("dynamic_tool",
            arguments -> executions.incrementAndGet());
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AgentToolResolver getToolResolver() {
                return (turn, name) -> "dynamic_tool".equals(name) ? dynamic : null;
            }
        };
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("dynamic-1", "dynamic_tool", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("dynamic-agent")
            .chatModel(model)
            .middleware(middleware)
            .build();

        AgentTurn completed = new AgentRunner().run(agent, "execute dynamic tool");

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, executions.get());
    }

    @Test
    public void shouldRejectAmbiguousMiddlewareToolResolvers() {
        Tool first = AgentScenarioTestSupport.tool("dynamic_tool", arguments -> "first");
        Tool second = AgentScenarioTestSupport.tool("dynamic_tool", arguments -> "second");
        AgentMiddleware firstMiddleware = resolverMiddleware(first);
        AgentMiddleware secondMiddleware = resolverMiddleware(second);
        Agent agent = Agent.builder("ambiguous-dynamic-agent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .middleware(firstMiddleware)
            .middleware(secondMiddleware)
            .build();
        AgentTurn turn = new AgentRunner().start(agent, "input");

        try {
            agent.resolveTool(turn, "dynamic_tool");
            fail("multiple resolvers must not silently select a tool");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("multiple AgentToolResolvers"));
        }
    }

    @Test
    public void shouldRejectToolResolverReturningDifferentToolName() {
        Tool wrong = AgentScenarioTestSupport.tool("other_tool", arguments -> "wrong");
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AgentToolResolver getToolResolver() {
                return (turn, name) -> "requested_tool".equals(name) ? wrong : null;
            }
        };
        Agent agent = Agent.builder("invalid-resolver-agent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .middleware(middleware)
            .build();
        AgentTurn turn = new AgentRunner().start(agent, "input");

        try {
            agent.resolveTool(turn, "requested_tool");
            fail("resolver must return the requested tool name");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("other_tool"));
            assertTrue(expected.getMessage().contains("requested_tool"));
        }
    }

    private AgentMiddleware resolverMiddleware(final Tool tool) {
        return new AgentMiddleware() {
            @Override
            public AgentToolResolver getToolResolver() {
                return (turn, name) -> tool.getName().equals(name) ? tool : null;
            }
        };
    }

    @Test
    public void shouldRestorePendingToolFromOriginalAgentVersion() {
        InMemoryAgentTurnStore durableStore = new InMemoryAgentTurnStore();
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
        AgentTurn turn = firstRunner.start(versionOne, "query order");
        try {
            firstRunner.step(turn);
            fail("Expected simulated process crash");
        } catch (SimulatedProcessCrash expected) {
            // ToolCall 已经保存，工具尚未执行。
        }

        AgentTurnSnapshot snapshot = durableStore.load(turn.getId());
        assertEquals("1", snapshot.getAgentVersion());
        assertEquals("query_order", snapshot.getState().getPendingToolCalls().get(0).getName());

        AgentRunner secondRunner = new AgentRunner(durableStore, agentLoader);
        AgentTurn completed = secondRunner.runUntilBlocked(turn.getId());

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("finished with version one", completed.getFinalOutput());
        assertEquals(1, versionOneExecutions.get());
        assertEquals(0, versionTwoExecutions.get());
    }

    @Test
    public void shouldFailWhenOriginalAgentVersionDoesNotContainPendingTool() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        Agent agent = Agent.builder("strict-tool-agent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .build();
        ToolCall call = new ToolCall("missing-call", "write_order", "{}");
        AgentTurnState state = AgentTurnState.builder("missing-tool-turn",
                agent.getExecutionPolicy(), System.currentTimeMillis())
            .status(AgentTurnStatus.RUNNING)
            .phase(AgentTurnPhase.TOOLS)
            .messages(Collections.emptyList())
            .pendingToolCalls(Collections.singletonList(call))
            .build();
        AgentTurnSnapshot invalid = AgentTurnSnapshot.of(agent.getId(), agent.getVersion(), state);
        store.save(invalid, -1);

        AgentTurn failed = new AgentRunner(store, new InMemoryAgentLoader(agent))
            .runUntilBlocked(invalid.getState().getTurnId());

        assertEquals(AgentTurnStatus.FAILED, failed.getStatus());
        assertTrue(failed.getError().getMessage().contains("tool not found: write_order"));
    }

    private static final class CrashAfterPendingSnapshotStore implements AgentTurnStore {
        private final AgentTurnStore delegate;
        private boolean crashed;

        private CrashAfterPendingSnapshotStore(AgentTurnStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AgentTurnSnapshot load(String turnId) {
            return delegate.load(turnId);
        }

        @Override
        public AgentTurnSnapshot findActiveTurn(String conversationId) {
            return delegate.findActiveTurn(conversationId);
        }

        @Override
        public long currentTimeMillis() {
            return delegate.currentTimeMillis();
        }

        @Override
        public boolean requestCancellation(String turnId) {
            return delegate.requestCancellation(turnId);
        }

        @Override
        public com.agentsflex.agent.store.ParentChildTurnSnapshots saveParentAndChild(
            AgentTurnSnapshot parent, long expectedParentVersion, AgentTurnSnapshot child) {
            return delegate.saveParentAndChild(parent, expectedParentVersion, child);
        }

        @Override
        public java.util.List<AgentTurnSnapshot> claimRunnable(
            String workerId, long now, long leaseMillis, int limit) {
            return delegate.claimRunnable(workerId, now, leaseMillis, limit);
        }

        @Override
        public AgentTurnSnapshot renewLease(String turnId, String workerId,
                                            String leaseId, long now, long leaseUntil) {
            return delegate.renewLease(turnId, workerId, leaseId, now, leaseUntil);
        }

        @Override
        public void releaseLease(String turnId, String workerId, String leaseId) {
            delegate.releaseLease(turnId, workerId, leaseId);
        }

        @Override
        public java.util.List<AgentTurnSnapshot> findTerminalChildrenWithWaitingParent(
            int limit) {
            return delegate.findTerminalChildrenWithWaitingParent(limit);
        }

        @Override
        public AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion) {
            AgentTurnSnapshot saved = delegate.save(snapshot, expectedVersion);
            if (!crashed && AgentTurnPhase.TOOLS.equals(saved.getState().getPhase())) {
                crashed = true;
                throw new SimulatedProcessCrash();
            }
            return saved;
        }
    }

    private static final class SimulatedProcessCrash extends RuntimeException {
    }
}

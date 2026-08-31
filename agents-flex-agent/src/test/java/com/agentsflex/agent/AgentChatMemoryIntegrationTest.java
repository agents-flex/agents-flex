/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.compression.*;

import com.agentsflex.agent.loader.AgentLoader;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.message.AgentActionMessage;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.UserMessage;
import com.alibaba.fastjson2.JSON;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentChatMemoryIntegrationTest {

    @Test
    public void shouldAutomaticallyAdvanceIncrementalCompressionWithoutProjectingSummary() {
        DefaultChatMemory memory = new DefaultChatMemory("compressed-conversation");
        memory.addMessage(new UserMessage("old question"));
        memory.addMessage(new AiMessage("old answer"));
        memory.addMessage(new UserMessage("recent question"));
        memory.addMessage(new AiMessage("recent answer"));
        memory.addMessage(new UserMessage("latest question"));
        memory.addMessage(new AiMessage("latest answer"));

        AtomicInteger compressions = new AtomicInteger();
        AgentContextCompressionStateStore states = new AgentContextCompressionStateStore() {
            private AgentContextCompressionState state;

            @Override
            public AgentContextCompressionState load(String conversationId) {
                return state;
            }

            @Override
            public boolean save(String conversationId, AgentContextCompressionState next,
                                long expectedVersion) {
                long actual = state == null ? 0 : state.getVersion();
                if (actual != expectedVersion) return false;
                state = next;
                return true;
            }
        };
        AgentContextCompressionCoordinator coordinator = new AgentContextCompressionCoordinator(
            states,
            input -> !input.getPendingMessages().isEmpty(),
            messages -> {
                compressions.incrementAndGet();
                return java.util.Arrays.asList(
                    new UserMessage("compressed facts"),
                    new AiMessage("compressed summary"));
            },
            messages -> messages.size());
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("new answer"));
        model.enqueue(prompt -> new AiMessage("second answer"));
        Agent agent = Agent.builder("incremental-agent")
            .chatModel(model)
            .compressionPolicy(AgentContextCompressionPolicy.incremental(coordinator))
            .build();

        AgentRunner runner = AgentRunner.builder()
            .chatMemoryProvider(id -> memory)
            .build();
        AgentTurn turn = runner.run(agent, "compressed-conversation", "new question");

        assertEquals(1, compressions.get());
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals("new answer", turn.getFinalOutput());
        List<Message> persisted = memory.getModelMessages(Integer.MAX_VALUE);
        assertTrue(persisted.stream().anyMatch(message -> "old question".equals(message.getTextContent())));
        assertTrue(persisted.stream().noneMatch(message -> "compressed facts".equals(message.getTextContent())));
        assertTrue(persisted.stream().anyMatch(message -> "new question".equals(message.getTextContent())));

        AgentTurn second = runner.run(agent, "compressed-conversation", "second question");
        assertEquals(AgentTurnStatus.COMPLETED, second.getStatus());
        assertEquals("second answer", second.getFinalOutput());
        assertEquals(2, compressions.get());
    }

    @Test
    public void shouldProjectApprovalAndResumeAcrossRunnerInstances() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("deploy-1", "deploy", "{}")));
        model.enqueue(prompt -> new AiMessage("deployed"));
        AtomicInteger executions = new AtomicInteger();
        Agent agent = Agent.builder("chat-memory-agent")
            .chatModel(model)
            .tool(tool("deploy", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((turn, call, value) ->
                ToolApprovalDecision.requireApproval()
                    .message("是否允许执行发布？")
                    .build())
            .build();
        InMemoryAgentTurnStore turnStore = new InMemoryAgentTurnStore();
        InMemoryAgentLoader loader = new InMemoryAgentLoader(agent);
        DefaultChatMemory memory = new DefaultChatMemory("conversation-1");

        AgentRunner firstRunner = runner(turnStore, loader, memory);
        AgentTurn waiting = firstRunner.run(agent, "conversation-1", "发布应用");

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals("conversation-1", waiting.getConversationId());
        assertEquals(3, memory.getMessages(Integer.MAX_VALUE).size());
        assertEquals(2, memory.getModelMessages(Integer.MAX_VALUE).size());
        AgentActionMessage pending = action(memory);
        assertEquals(AgentActionMessage.Status.PENDING, pending.getStatus());
        assertEquals(2, pending.getActions().size());
        assertFalse(pending.isModelVisible());

        AgentRunner secondRunner = runner(turnStore, loader, memory);
        AgentTurn ready = secondRunner.submitResume(waiting.getId(),
            AgentResumeCommand.approveTool("deploy-1")
                .withMetadata("operatorId", "user-7"));

        assertEquals(AgentTurnStatus.RUNNING, ready.getStatus());
        AgentActionMessage approved = action(memory);
        assertEquals(AgentActionMessage.Status.APPROVED, approved.getStatus());
        assertEquals("user-7", approved.getResolvedBy());
        assertEquals(1, approved.getVersion());
        assertTrue(approved.getActions().isEmpty());

        AgentTurn completed = secondRunner.runUntilBlocked(waiting.getId());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, executions.get());
        assertEquals("deployed", completed.getFinalOutput());
        assertEquals(5, memory.getMessages(Integer.MAX_VALUE).size());
        assertEquals(4, memory.getModelMessages(Integer.MAX_VALUE).size());

        secondRunner.restore(waiting.getId());
        assertEquals(5, memory.getMessages(Integer.MAX_VALUE).size());
        assertEquals(AgentActionMessage.Status.APPROVED, action(memory).getStatus());
    }

    @Test
    public void shouldRejectNewConversationTurnWhileAnotherTurnIsActive() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("approval-1", "deploy", "{}")));
        Agent agent = Agent.builder("busy-conversation-agent")
            .chatModel(model)
            .tool(tool("deploy", args -> "deployed"))
            .toolApprovalPolicy((turn, call, value) ->
                ToolApprovalDecision.requireApproval().message("需要审批").build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        InMemoryAgentLoader loader = new InMemoryAgentLoader(agent);
        DefaultChatMemory memory = new DefaultChatMemory("busy-conversation");
        AgentRunner first = runner(store, loader, memory);
        AgentRunner second = runner(store, loader, memory);

        AgentTurn waiting = first.run(agent, "busy-conversation", "执行发布");
        try {
            second.run(agent, "busy-conversation", "再执行一个请求");
            fail("an active conversation must reject a new turn");
        } catch (AgentConversationBusyException error) {
            assertEquals("busy-conversation", error.getConversationId());
            assertEquals(waiting.getId(), error.getActiveTurnId());
            assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, error.getStatus());
        }
    }

    @Test
    public void shouldCloseCancelledToolCallHistoryBeforeNextConversationTurn() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("cancel-1", "deploy", "{}")));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertTrue(messages.size() >= 2);
            assertTrue(messages.get(messages.size() - 2) instanceof AiMessage);
            return new AiMessage("next answer");
        });
        Agent agent = Agent.builder("cancel-history-agent")
            .chatModel(model)
            .tool(tool("deploy", args -> "deployed"))
            .toolApprovalPolicy((turn, call, value) ->
                ToolApprovalDecision.requireApproval().message("需要审批").build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        InMemoryAgentLoader loader = new InMemoryAgentLoader(agent);
        DefaultChatMemory memory = new DefaultChatMemory("cancel-history");
        AgentRunner runner = runner(store, loader, memory);

        AgentTurn waiting = runner.run(agent, "cancel-history", "执行发布");
        runner.requestCancellation(waiting.getId());
        AgentTurn cancelled = runner.runUntilBlocked(waiting.getId());

        assertEquals(AgentTurnStatus.CANCELLED, cancelled.getStatus());
        List<Message> history = memory.getModelMessages(Integer.MAX_VALUE);
        assertTrue(history.get(history.size() - 1) instanceof AiMessage);

        AgentTurn next = runner.run(agent, "cancel-history", "开始新的问题");
        assertEquals(AgentTurnStatus.COMPLETED, next.getStatus());
        assertEquals("next answer", next.getFinalOutput());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRequireProviderOnlyForConversationApi() {
        Agent agent = Agent.builder("missing-memory-agent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .build();

        new AgentRunner().start(agent, "conversation-1", "hello");
    }

    @Test
    public void shouldSerializeActionMessageStorageFields() {
        AgentActionMessage source = AgentActionMessage.toolApproval(
            "turn-1", "call-1", "approve?");
        source.setVersion(2);

        AgentActionMessage restored = JSON.parseObject(
            JSON.toJSONString(source), AgentActionMessage.class);

        assertEquals(source.getMessageId(), restored.getMessageId());
        assertEquals(2, restored.getVersion());
        assertFalse(restored.isModelVisible());
        assertEquals(AgentActionMessage.Status.PENDING, restored.getStatus());
        assertEquals("call-1", restored.getActionId());
    }

    @Test
    public void shouldLoadOnlyConfiguredConversationWindowIntoRun() {
        DefaultChatMemory memory = new DefaultChatMemory("bounded-conversation");
        for (int index = 0; index < 10; index++) {
            memory.addMessage(new com.agentsflex.core.message.UserMessage("history-" + index));
        }
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals(3, messages.size());
            assertEquals("history-8", messages.get(0).getTextContent());
            assertEquals("history-9", messages.get(1).getTextContent());
            assertEquals("latest", messages.get(2).getTextContent());
            return new AiMessage("done");
        });
        Agent agent = Agent.builder("bounded-history-agent")
            .chatModel(model)
            .maxAttachedMessages(3)
            .build();

        AgentTurn turn = AgentRunner.builder()
            .chatMemoryProvider(id -> memory)
            .build()
            .run(agent, "bounded-conversation", "latest");

        // 只复制 3 条历史、本轮 UserMessage 和最终 AiMessage，不复制前 7 条业务历史。
        assertEquals(5, turn.getConversationHistory().size());
        assertEquals(12, memory.getMessages(Integer.MAX_VALUE).size());
    }

    @Test
    public void shouldLoadActiveAgentByIdForNewConversationTurn() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("loaded"));
        Agent agent = Agent.builder("loader-entry-agent")
            .id("loader-entry-agent")
            .version("2")
            .chatModel(model)
            .build();
        DefaultChatMemory memory = new DefaultChatMemory("conversation-by-id");
        AgentRunner runner = runner(new InMemoryAgentTurnStore(),
            new InMemoryAgentLoader(agent), memory);

        AgentTurn turn = runner.run("loader-entry-agent", "conversation-by-id",
            new com.agentsflex.core.message.UserMessage("hello"),
            AgentTurnOptions.builder().metadata("requestId", "request-1").build());

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals("loader-entry-agent", turn.getAgent().getId());
        assertEquals("2", turn.getAgent().getVersion());
        assertEquals("conversation-by-id", turn.getConversationId());
        assertEquals("loaded", turn.getFinalOutput());
        assertEquals(2, memory.getModelMessages(Integer.MAX_VALUE).size());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectUnknownActiveAgentId() {
        DefaultChatMemory memory = new DefaultChatMemory("missing-agent-conversation");

        runner(new InMemoryAgentTurnStore(), new InMemoryAgentLoader(), memory)
            .start("missing-agent", "missing-agent-conversation", "hello");
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectMismatchedAgentReturnedByLoader() {
        Agent unexpected = Agent.builder("unexpected-agent")
            .id("unexpected-agent")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .build();
        AgentLoader loader = new AgentLoader() {
            @Override
            public Agent load(String agentId, String version) {
                return unexpected;
            }

            @Override
            public Agent loadActive(String agentId) {
                return unexpected;
            }
        };
        DefaultChatMemory memory = new DefaultChatMemory("mismatched-agent-conversation");

        AgentRunner.builder()
            .agentLoader(loader)
            .chatMemoryProvider(id -> memory)
            .build()
            .start("expected-agent", "mismatched-agent-conversation", "hello");
    }

    private static AgentRunner runner(InMemoryAgentTurnStore store,
                                      InMemoryAgentLoader loader,
                                      DefaultChatMemory memory) {
        return AgentRunner.builder()
            .turnStore(store)
            .agentLoader(loader)
            .chatMemoryProvider(id -> memory)
            .build();
    }

    private static AgentActionMessage action(DefaultChatMemory memory) {
        List<Message> messages = memory.getMessages(Integer.MAX_VALUE);
        for (Message message : messages) {
            if (message instanceof AgentActionMessage) {
                return (AgentActionMessage) message;
            }
        }
        throw new AssertionError("AgentActionMessage not found");
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.message.AgentActionMessage;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.alibaba.fastjson2.JSON;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentChatMemoryIntegrationTest {

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

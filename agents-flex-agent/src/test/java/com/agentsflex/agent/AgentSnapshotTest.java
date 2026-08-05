/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentTurnStore;
import com.agentsflex.agent.store.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentSnapshotTest {

    @Test
    public void shouldRestorePendingToolsWithoutCallingModelAgain() {
        InMemoryAgentTurnStore durableStore = new InMemoryAgentTurnStore();
        FailAfterPendingSnapshotStore crashingStore = new FailAfterPendingSnapshotStore(durableStore);
        QueueChatModel model = new QueueChatModel();
        AtomicInteger toolInvocations = new AtomicInteger();
        model.enqueue(prompt -> aiWithCalls(new ToolCall("call-1", "work", "{}")));
        model.enqueue(prompt -> new AiMessage("finished"));

        Agent agent = Agent.builder("durable-agent")
            .chatModel(model)
            .tool(tool("work", args -> toolInvocations.incrementAndGet()))
            .build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner firstProcess = new AgentRunner(crashingStore, registry);
        AgentTurn turn = firstProcess.start(agent, "start");

        try {
            firstProcess.step(turn);
            fail("Expected simulated process crash");
        } catch (SimulatedProcessCrash expected) {
            // Snapshot 已写入，模拟进程在执行工具前退出。
        }

        assertEquals(1, model.getCallCount());
        assertEquals(0, toolInvocations.get());
        AgentTurnSnapshot pending = durableStore.load(turn.getId());
        assertEquals(AgentTurnPhase.TOOLS, pending.getState().getPhase());
        assertEquals(1, pending.getState().getPendingToolCalls().size());

        AgentRunner secondProcess = new AgentRunner(durableStore, registry);
        AgentTurn restored = secondProcess.restore(turn.getId());
        AgentStepResult toolStep = secondProcess.step(restored);

        assertEquals(1, toolStep.getToolMessages().size());
        assertEquals(AgentTurnStatus.RUNNING, restored.getStatus());
        assertEquals(1, model.getCallCount());
        assertEquals(1, toolInvocations.get());
        assertTrue(restored.getPendingToolCalls().isEmpty());

        secondProcess.runUntilBlocked(restored);
        assertEquals(AgentTurnStatus.COMPLETED, restored.getStatus());
        assertEquals("finished", restored.getFinalOutput());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldKeepStoredSnapshotIsolatedFromLiveRun() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        Agent agent = Agent.builder("snapshot-agent")
            .chatModel(new QueueChatModel())
            .build();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentTurn turn = runner.start(agent, "original");

        turn.getPrompt().addUserMessage("added after snapshot");

        AgentTurnSnapshot stored = store.load(turn.getId());
        assertEquals(1, stored.getState().getMessages().size());
        assertEquals("original", stored.getState().getMessages().get(0).getTextContent());
    }

    @Test
    public void shouldDetectOptimisticLockConflict() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        Agent agent = Agent.builder("version-agent")
            .chatModel(new QueueChatModel())
            .build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentTurn original = runner.start(agent, "input");
        AgentTurn firstCopy = runner.restore(original.getId());
        AgentTurn staleCopy = runner.restore(original.getId());

        firstCopy.putMetadata("owner", "first");
        runner.saveSnapshot(firstCopy);

        try {
            staleCopy.putMetadata("owner", "stale");
            runner.saveSnapshot(staleCopy);
            fail("Expected version conflict");
        } catch (AgentTurnVersionConflictException expected) {
            assertTrue(expected.getMessage().contains(original.getId()));
        }
    }

    @Test
    public void shouldSuspendRestoreAndResumeWithUserInput() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals(2, messages.size());
            assertEquals("Shanghai", messages.get(1).getTextContent());
            return new AiMessage("sunny");
        });
        Agent agent = Agent.builder("interactive-agent").chatModel(model).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentTurn turn = runner.start(agent, "weather?");

        runner.suspend(turn, AgentSuspension.userInput("Which city?"));
        assertEquals(AgentTurnStatus.WAITING_FOR_USER, turn.getStatus());
        assertTrue(turn.getStatus().isBlocked());

        AgentTurn restored = runner.runUntilBlocked(turn.getId());
        assertEquals(AgentTurnStatus.WAITING_FOR_USER, restored.getStatus());
        assertEquals(0, model.getCallCount());
        assertNotNull(restored.getSuspension());

        AgentTurn completed = runner.resume(turn.getId(), AgentResumeCommand.userInput("Shanghai"));
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("sunny", completed.getFinalOutput());
        assertEquals(1, model.getCallCount());
    }

    @Test
    public void shouldRejectContinueWhenUserInputIsRequired() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        Agent agent = Agent.builder("interactive-agent")
            .chatModel(new QueueChatModel())
            .build();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentTurn turn = runner.start(agent, "weather?");
        runner.suspend(turn, AgentSuspension.userInput("Which city?"));

        try {
            runner.resume(turn.getId(), AgentResumeCommand.continueTurn());
            fail("Expected USER_INPUT command validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("USER_INPUT"));
        }

        AgentTurn restored = runner.restore(turn.getId());
        assertEquals(AgentTurnStatus.WAITING_FOR_USER, restored.getStatus());
        assertEquals(1, restored.getPrompt().getMessages().size());
    }

    @Test
    public void shouldPersistAccumulatedTokenUsage() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> {
            AiMessage message = new AiMessage("done");
            message.setPromptTokens(12);
            message.setCompletionTokens(5);
            message.setTotalTokens(17);
            return message;
        });
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        Agent agent = Agent.builder("budget-agent").chatModel(model).build();

        AgentTurn turn = runner.run(agent, "count tokens");
        AgentTurnSnapshot snapshot = store.load(turn.getId());

        assertEquals(12, turn.getInputTokens());
        assertEquals(5, turn.getOutputTokens());
        assertEquals(17, turn.getTotalTokens());
        assertEquals(17, snapshot.getState().getTotalTokens());
    }

    private static Tool tool(String name, Function<Map<String, Object>, Object> function) {
        return new Tool() {
            @Override
            public String getName() { return name; }

            @Override
            public String getDescription() { return name; }

            @Override
            public Parameter[] getParameters() { return new Parameter[0]; }

            @Override
            public Object invoke(Map<String, Object> argsMap) { return function.apply(argsMap); }
        };
    }

    private static AiMessage aiWithCalls(ToolCall... calls) {
        AiMessage message = new AiMessage();
        message.setToolCalls(Arrays.asList(calls));
        return message;
    }

    private static AiMessageResponse response(Prompt prompt, AiMessage message) {
        ChatContext context = new ChatContext();
        context.setPrompt(prompt);
        return new AiMessageResponse(context, null, message);
    }

    private static class QueueChatModel implements ChatModel {

        private final Deque<Function<Prompt, AiMessage>> responses = new ArrayDeque<>();
        private int callCount;

        void enqueue(Function<Prompt, AiMessage> response) {
            responses.add(response);
        }

        int getCallCount() {
            return callCount;
        }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            callCount++;
            assertFalse("No queued response for model call " + callCount, responses.isEmpty());
            return response(prompt, responses.removeFirst().apply(prompt));
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FailAfterPendingSnapshotStore implements AgentTurnStore {

        private final AgentTurnStore delegate;
        private boolean failed;

        private FailAfterPendingSnapshotStore(AgentTurnStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AgentTurnSnapshot load(String turnId) {
            return delegate.load(turnId);
        }

        @Override
        public boolean requestCancellation(String turnId) {
            return delegate.requestCancellation(turnId);
        }

        @Override
        public boolean isCancellationRequested(String turnId) {
            return delegate.isCancellationRequested(turnId);
        }

        @Override
        public AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion) {
            AgentTurnSnapshot saved = delegate.save(snapshot, expectedVersion);
            if (!failed && AgentTurnPhase.TOOLS.equals(saved.getState().getPhase())) {
                failed = true;
                throw new SimulatedProcessCrash();
            }
            return saved;
        }
    }

    private static class SimulatedProcessCrash extends RuntimeException {
    }
}

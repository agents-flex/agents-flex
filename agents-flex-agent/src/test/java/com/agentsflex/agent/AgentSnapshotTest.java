/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentRunStore;
import com.agentsflex.agent.store.AgentRunVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
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
        InMemoryAgentRunStore durableStore = new InMemoryAgentRunStore();
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
        AgentRun run = firstProcess.start(agent, "start");

        try {
            firstProcess.step(run);
            fail("Expected simulated process crash");
        } catch (SimulatedProcessCrash expected) {
            // Snapshot 已写入，模拟进程在执行工具前退出。
        }

        assertEquals(1, model.getCallCount());
        assertEquals(0, toolInvocations.get());
        AgentRunSnapshot pending = durableStore.load(run.getId());
        assertEquals(AgentRunPhase.TOOLS, pending.getPhase());
        assertEquals(1, pending.getPendingToolCalls().size());

        AgentRunner secondProcess = new AgentRunner(durableStore, registry);
        AgentRun restored = secondProcess.restore(run.getId());
        AgentStepResult toolStep = secondProcess.step(restored);

        assertEquals(AgentStepType.TOOLS_EXECUTED, toolStep.getType());
        assertEquals(1, model.getCallCount());
        assertEquals(1, toolInvocations.get());
        assertTrue(restored.getPendingToolCalls().isEmpty());

        secondProcess.runUntilBlocked(restored);
        assertEquals(AgentRunStatus.COMPLETED, restored.getStatus());
        assertEquals("finished", restored.getFinalOutput());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldKeepStoredSnapshotIsolatedFromLiveRun() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Agent agent = Agent.builder("snapshot-agent")
            .chatModel(new QueueChatModel())
            .build();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentRun run = runner.start(agent, "original");

        run.getPrompt().addUserMessage("added after snapshot");

        AgentRunSnapshot stored = store.load(run.getId());
        assertEquals(1, stored.getMessages().size());
        assertEquals("original", stored.getMessages().get(0).getTextContent());
    }

    @Test
    public void shouldDetectOptimisticLockConflict() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Agent agent = Agent.builder("version-agent")
            .chatModel(new QueueChatModel())
            .build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentRun original = runner.start(agent, "input");
        AgentRun firstCopy = runner.restore(original.getId());
        AgentRun staleCopy = runner.restore(original.getId());

        firstCopy.putMetadata("owner", "first");
        runner.saveSnapshot(firstCopy);

        try {
            staleCopy.putMetadata("owner", "stale");
            runner.saveSnapshot(staleCopy);
            fail("Expected version conflict");
        } catch (AgentRunVersionConflictException expected) {
            assertTrue(expected.getMessage().contains(original.getId()));
        }
    }

    @Test
    public void shouldSuspendRestoreAndResumeWithUserInput() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
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
        AgentRun run = runner.start(agent, "weather?");

        runner.suspend(run, AgentSuspension.userInput("Which city?"));
        assertEquals(AgentRunStatus.WAITING_FOR_USER, run.getStatus());
        assertTrue(run.getStatus().isBlocked());

        AgentRun restored = runner.runUntilBlocked(run.getId());
        assertEquals(AgentRunStatus.WAITING_FOR_USER, restored.getStatus());
        assertEquals(0, model.getCallCount());
        assertNotNull(restored.getSuspension());

        AgentRun completed = runner.resume(run.getId(), AgentResumeCommand.userInput("Shanghai"));
        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("sunny", completed.getFinalOutput());
        assertEquals(1, model.getCallCount());
    }

    @Test
    public void shouldRejectContinueWhenUserInputIsRequired() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Agent agent = Agent.builder("interactive-agent")
            .chatModel(new QueueChatModel())
            .build();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentRun run = runner.start(agent, "weather?");
        runner.suspend(run, AgentSuspension.userInput("Which city?"));

        try {
            runner.resume(run.getId(), AgentResumeCommand.continueRun());
            fail("Expected USER_INPUT command validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("USER_INPUT"));
        }

        AgentRun restored = runner.restore(run.getId());
        assertEquals(AgentRunStatus.WAITING_FOR_USER, restored.getStatus());
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
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        Agent agent = Agent.builder("budget-agent").chatModel(model).build();

        AgentRun run = runner.run(agent, "count tokens");
        AgentRunSnapshot snapshot = store.load(run.getId());

        assertEquals(12, run.getInputTokens());
        assertEquals(5, run.getOutputTokens());
        assertEquals(17, run.getTotalTokens());
        assertEquals(17, snapshot.getTotalTokens());
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

    private static class FailAfterPendingSnapshotStore implements AgentRunStore {

        private final AgentRunStore delegate;
        private boolean failed;

        private FailAfterPendingSnapshotStore(AgentRunStore delegate) {
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
            if (!failed && AgentRunPhase.TOOLS.equals(saved.getPhase())) {
                failed = true;
                throw new SimulatedProcessCrash();
            }
            return saved;
        }
    }

    private static class SimulatedProcessCrash extends RuntimeException {
    }
}

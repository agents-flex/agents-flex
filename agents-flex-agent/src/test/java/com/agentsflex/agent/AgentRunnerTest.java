/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.tool.ToolErrorStrategy;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AgentRunnerTest {

    @Test
    public void shouldCompleteWithoutToolsAndApplyAgentConfiguration() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> {
            assertTrue(prompt.getMessages().get(0) instanceof SystemMessage);
            assertEquals("Answer precisely", prompt.getMessages().get(0).getTextContent());
            assertEquals("lookup", prompt.getTools().get(0).getName());
            return new AiMessage("done");
        });

        Agent agent = Agent.builder("assistant")
            .instructions("Answer precisely")
            .chatModel(model)
            .tool(tool("lookup", args -> "value"))
            .build();

        AgentRun run = new AgentRunner().run(agent, "question");

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals("done", run.getFinalOutput());
        assertEquals(1, run.getIterationCount());
        assertNull(run.getError());
    }

    @Test
    public void shouldExecuteNativeToolCallAndPreserveProtocolMessages() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> aiWithCalls(toolCall("call-1", "weather", "{\"city\":\"Shanghai\"}")));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals(3, messages.size());
            assertTrue(messages.get(1) instanceof AiMessage);
            assertTrue(((AiMessage) messages.get(1)).hasToolCalls());
            assertTrue(messages.get(2) instanceof ToolMessage);
            ToolMessage toolMessage = (ToolMessage) messages.get(2);
            assertEquals("call-1", toolMessage.getToolCallId());
            assertEquals("sunny in Shanghai", toolMessage.getContent());
            return new AiMessage("It is sunny.");
        });

        Agent agent = Agent.builder()
            .chatModel(model)
            .tool(tool("weather", args -> "sunny in " + args.get("city")))
            .build();
        AgentRunner runner = new AgentRunner();
        AgentRun run = AgentRun.start(agent, "weather?");

        AgentStepResult first = runner.step(run);
        assertEquals(AgentStepType.TOOLS_EXECUTED, first.getType());
        assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        assertEquals("call-1", first.getToolMessages().get(0).getToolCallId());

        AgentStepResult second = runner.step(run);
        assertEquals(AgentStepType.COMPLETED, second.getType());
        assertEquals("It is sunny.", run.getFinalOutput());
    }

    @Test
    public void shouldExecuteMultipleToolCallsInModelOrder() {
        List<String> executionOrder = new ArrayList<>();
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> aiWithCalls(
            toolCall("call-a", "first", "{}"),
            toolCall("call-b", "second", "{}")
        ));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            ToolMessage first = (ToolMessage) messages.get(messages.size() - 2);
            ToolMessage second = (ToolMessage) messages.get(messages.size() - 1);
            assertEquals("call-a", first.getToolCallId());
            assertEquals("call-b", second.getToolCallId());
            return new AiMessage("complete");
        });

        Agent agent = Agent.builder()
            .chatModel(model)
            .tool(tool("first", args -> {
                executionOrder.add("first");
                return 1;
            }))
            .tool(tool("second", args -> {
                executionOrder.add("second");
                return 2;
            }))
            .build();

        AgentRun run = new AgentRunner().run(agent, "run both");

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals(Arrays.asList("first", "second"), executionOrder);
    }

    @Test
    public void shouldReturnToolFailureToModelWhenConfigured() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> aiWithCalls(toolCall("call-fail", "unstable", "{}")));
        model.enqueue(prompt -> {
            ToolMessage error = (ToolMessage) prompt.getMessages().get(2);
            assertEquals("call-fail", error.getToolCallId());
            assertTrue(error.getContent().contains("tool_execution_error"));
            assertTrue(error.getContent().contains("service unavailable"));
            return new AiMessage("The service is unavailable.");
        });

        Agent agent = Agent.builder()
            .chatModel(model)
            .tool(tool("unstable", args -> {
                throw new RuntimeException("service unavailable");
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolErrorStrategy(ToolErrorStrategy.RETURN_ERROR_TO_MODEL)
                .build())
            .build();

        AgentRun run = new AgentRunner().run(agent, "try it");

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals("The service is unavailable.", run.getFinalOutput());
    }

    @Test
    public void shouldFailWhenModelCallsUnknownTool() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> aiWithCalls(toolCall("missing-1", "missing", "{}")));

        Agent agent = Agent.builder().chatModel(model).build();
        AgentRun run = new AgentRunner().run(agent, "call something");

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertNotNull(run.getError());
        assertTrue(run.getError().getMessage().contains("tool not found: missing"));
        assertNull(run.getFinalMessage());
    }

    @Test
    public void shouldStopAtMaximumIterations() {
        AtomicInteger toolInvocations = new AtomicInteger();
        ChatModel model = new RepeatingToolCallModel();
        Agent agent = Agent.builder()
            .chatModel(model)
            .tool(tool("again", args -> toolInvocations.incrementAndGet()))
            .executionPolicy(AgentExecutionPolicy.builder().maxIterations(2).build())
            .build();

        AgentRun run = new AgentRunner().run(agent, "keep going");

        assertEquals(AgentRunStatus.MAX_ITERATIONS_REACHED, run.getStatus());
        assertEquals(2, run.getIterationCount());
        assertEquals(2, toolInvocations.get());
        assertNull(run.getFinalMessage());
    }

    @Test
    public void shouldCancelBeforeCallingModel() {
        QueueChatModel model = new QueueChatModel();
        Agent agent = Agent.builder().chatModel(model).build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent));
        AgentRun run = runner.start(agent, "cancel me");
        runner.requestCancellation(run.getId());

        AgentRun cancelled = runner.restore(run.getId());
        AgentStepResult result = runner.step(cancelled);

        assertEquals(AgentStepType.CANCELLED, result.getType());
        assertEquals(AgentRunStatus.CANCELLED, cancelled.getStatus());
        assertEquals(0, model.getCallCount());
    }

    @Test
    public void shouldEmitLifecycleEvents() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> aiWithCalls(toolCall("event-1", "eventTool", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        List<String> events = new ArrayList<>();

        Agent agent = Agent.builder()
            .chatModel(model)
            .tool(tool("eventTool", args -> "ok"))
            .build();

        AgentRun run = new AgentRunner().addEventListener(event -> {
            if (event.getType() == AgentEventType.RUN_STARTED) events.add("run-start");
            if (event.getType() == AgentEventType.MODEL_STARTED) events.add("model-start");
            if (event.getType() == AgentEventType.TOOL_STARTED) events.add("tool-start");
            if (event.getType() == AgentEventType.TOOL_COMPLETED) events.add("tool-end");
            if (event.getType() == AgentEventType.RUN_COMPLETED) events.add("run-complete");
        }).run(agent, "events");

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals(Arrays.asList(
            "run-start", "model-start", "tool-start", "tool-end", "model-start", "run-complete"
        ), events);
    }

    @Test
    public void shouldApplyAgentToolInterceptors() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> aiWithCalls(toolCall("intercept-1", "secured", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        AtomicInteger intercepted = new AtomicInteger();
        ToolInterceptor interceptor = (context, chain) -> {
            intercepted.incrementAndGet();
            return chain.proceed(context);
        };

        Agent agent = Agent.builder()
            .chatModel(model)
            .tool(tool("secured", args -> "ok"))
            .toolInterceptor(interceptor)
            .build();

        AgentRun run = new AgentRunner().run(agent, "execute");

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals(1, intercepted.get());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectDuplicateToolNames() {
        QueueChatModel model = new QueueChatModel();
        Agent.builder()
            .chatModel(model)
            .tool(tool("duplicate", args -> "one"))
            .tool(tool("duplicate", args -> "two"))
            .build();
    }

    private static Tool tool(String name, Function<Map<String, Object>, Object> function) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Parameter[] getParameters() {
                return new Parameter[0];
            }

            @Override
            public Object invoke(Map<String, Object> argsMap) {
                return function.apply(argsMap);
            }
        };
    }

    private static AiMessage aiWithCalls(ToolCall... calls) {
        AiMessage message = new AiMessage();
        message.setToolCalls(Arrays.asList(calls));
        return message;
    }

    private static ToolCall toolCall(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments);
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

    private static class RepeatingToolCallModel implements ChatModel {

        private int callCount;

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            callCount++;
            return response(prompt, aiWithCalls(toolCall("again-" + callCount, "again", "{}")));
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}

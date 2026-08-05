/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentModelCallChain;
import com.agentsflex.agent.middleware.AgentStepChain;
import com.agentsflex.agent.middleware.AgentToolCallChain;
import com.agentsflex.agent.middleware.AgentToolCallContext;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.agentsflex.agent.AgentScenarioTestSupport.response;
import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Middleware 的转换、短路、异常和清理契约测试。 */
public class AgentMiddlewareContractTest {

    @Test
    public void shouldPassReplacedPromptToModel() {
        AtomicReference<String> observed = new AtomicReference<>();
        ChatModel model = observingModel(observed, "model-result");
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                     AgentModelCallChain chain) {
                context.setPrompt(new SimplePrompt("rewritten"));
                return chain.proceed(context);
            }
        };

        AgentTurn turn = new AgentRunner().run(Agent.builder("prompt-middleware")
            .chatModel(model).middleware(middleware).build(), "original");

        assertEquals("rewritten", observed.get());
        assertEquals("model-result", turn.getFinalOutput());
    }

    @Test
    public void shouldUseResponseTransformedByModelMiddleware() {
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                     AgentModelCallChain chain) {
                AiMessageResponse original = chain.proceed(context);
                return response(original.getContext().getPrompt(), new AiMessage("transformed"));
            }
        };

        AgentTurn turn = new AgentRunner().run(Agent.builder("response-middleware")
            .chatModel(observingModel(new AtomicReference<String>(), "raw"))
            .middleware(middleware).build(), "input");

        assertEquals("transformed", turn.getFinalOutput());
    }

    @Test
    public void shouldShortCircuitToolWithoutExecutingImplementation() {
        AgentScenarioTestSupport.QueueChatModel model = modelWithToolThenText("cached result");
        AtomicInteger executions = new AtomicInteger();
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public Object aroundToolCall(AgentToolCallContext context,
                                         AgentToolCallChain chain) {
                return "cached";
            }
        };
        Agent agent = Agent.builder("tool-short-circuit")
            .chatModel(model)
            .tool(tool("lookup", args -> executions.incrementAndGet()))
            .middleware(middleware)
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "input");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(0, executions.get());
        assertEquals("cached", lastToolMessage(turn).getContent());
    }

    @Test
    public void shouldTransformToolResultAfterExecution() {
        AgentScenarioTestSupport.QueueChatModel model = modelWithToolThenText("done");
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public Object aroundToolCall(AgentToolCallContext context,
                                         AgentToolCallChain chain) {
                return "wrapped-" + chain.proceed(context);
            }
        };
        Agent agent = Agent.builder("tool-transform")
            .chatModel(model)
            .tool(tool("lookup", args -> "raw"))
            .middleware(middleware)
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "input");

        assertEquals("wrapped-raw", lastToolMessage(turn).getContent());
    }

    @Test
    public void shouldRunOuterFinallyWhenInnerMiddlewareFails() {
        AtomicBoolean cleaned = new AtomicBoolean();
        AgentMiddleware outer = new AgentMiddleware() {
            @Override
            public AgentStepResult aroundStep(AgentMiddlewareContext context,
                                              AgentStepChain chain) {
                try {
                    return chain.proceed(context);
                } finally {
                    cleaned.set(true);
                }
            }
        };
        AgentMiddleware inner = new AgentMiddleware() {
            @Override
            public AgentStepResult aroundStep(AgentMiddlewareContext context,
                                              AgentStepChain chain) {
                throw new RuntimeException("failed");
            }
        };
        AgentRunner runner = new AgentRunner();
        AgentTurn turn = runner.start(Agent.builder("middleware-finally")
            .chatModel(observingModel(new AtomicReference<String>(), "unused"))
            .middleware(outer).middleware(inner).build(), "input");

        try {
            runner.step(turn);
        } catch (RuntimeException expected) {
            assertEquals("failed", expected.getMessage());
        }
        assertTrue(cleaned.get());
    }

    @Test
    public void shouldConvertToolMiddlewareFailureToToolMessageWhenConfigured() {
        AgentScenarioTestSupport.QueueChatModel model = modelWithToolThenText("recovered");
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public Object aroundToolCall(AgentToolCallContext context,
                                         AgentToolCallChain chain) {
                throw new RuntimeException("policy unavailable");
            }
        };
        Agent agent = Agent.builder("tool-middleware-error")
            .chatModel(model)
            .tool(tool("lookup", args -> "unused"))
            .middleware(middleware)
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolErrorStrategy(com.agentsflex.agent.tool.ToolErrorStrategy.RETURN_ERROR_TO_MODEL)
                .build())
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "input");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertTrue(lastToolMessage(turn).getContent().contains("policy unavailable"));
    }

    private ChatModel observingModel(AtomicReference<String> observed, String result) {
        return new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                List<com.agentsflex.core.message.Message> messages = prompt.getMessages();
                observed.set(messages.isEmpty() ? null
                    : messages.get(messages.size() - 1).getTextContent());
                ChatContext context = new ChatContext();
                context.setPrompt(prompt);
                return new AiMessageResponse(context, null, new AiMessage(result));
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener,
                                   ChatOptions options) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private AgentScenarioTestSupport.QueueChatModel modelWithToolThenText(String text) {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("call-1", "lookup", "{}")));
        model.enqueue(prompt -> new AiMessage(text));
        return model;
    }

    private ToolMessage lastToolMessage(AgentTurn turn) {
        List<com.agentsflex.core.message.Message> messages = turn.getPrompt()
            .getMemory().getMessages(Integer.MAX_VALUE);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolMessage) return (ToolMessage) messages.get(i);
        }
        return null;
    }
}

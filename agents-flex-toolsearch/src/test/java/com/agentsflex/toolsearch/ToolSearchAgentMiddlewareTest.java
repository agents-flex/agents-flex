package com.agentsflex.toolsearch;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** ToolSearch 通过 Middleware 接入 AgentRunner 的恢复与可见性契约测试。 */
public class ToolSearchAgentMiddlewareTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldRestoreActivatedToolsAndExecuteThroughMiddlewareResolver() {
        AtomicInteger weatherExecutions = new AtomicInteger();
        Tool weather = Tool.builder("weatherLookup", arguments -> {
            weatherExecutions.incrementAndGet();
            return "sunny";
        }).description("Look up weather forecasts").build();
        ToolSearchTool searchTool = ToolSearchTool.builder().addTool(weather).build();
        ToolSearchAgentMiddleware middleware = ToolSearchAgentMiddleware.of(searchTool);
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> {
            assertNotNull(prompt.getToolsMap().get(ToolSearchTool.NAME));
            assertNull(prompt.getToolsMap().get("weatherLookup"));
            return toolCalls(new ToolCall("search-1", ToolSearchTool.NAME,
                "{\"query\":\"weather forecast\"}"));
        });
        model.enqueue(prompt -> {
            assertNotNull(prompt.getToolsMap().get(ToolSearchTool.NAME));
            assertNotNull(prompt.getToolsMap().get("weatherLookup"));
            return toolCalls(new ToolCall("weather-1", "weatherLookup", "{}"));
        });
        model.enqueue(prompt -> new AiMessage("The weather is sunny"));
        Agent agent = Agent.builder("search-agent")
            .chatModel(model)
            .middleware(middleware)
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentTurn turn = runner.start(agent, "What is the weather?");

        runner.step(turn);
        runner.step(turn);
        AgentTurn restored = runner.restore(turn.getId());
        List<String> active = (List<String>) restored.getMetadata()
            .get(ToolSearchAgentMiddleware.ACTIVE_TOOL_NAMES_METADATA);

        assertEquals(Collections.singletonList("weatherLookup"), active);
        assertNotNull(agent.resolveTool(restored, "weatherLookup"));
        assertNull(agent.resolveTool(restored, "unknownTool"));

        AgentTurn completed = runner.run(restored);

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("The weather is sunny", completed.getFinalOutput());
        assertEquals(1, weatherExecutions.get());
        assertEquals(3, model.callCount);
    }

    @Test
    public void shouldNotResolveSearchableToolBeforeItIsActivated() {
        Tool hidden = Tool.builder("hiddenTool", arguments -> "hidden")
            .description("A hidden capability").build();
        ToolSearchAgentMiddleware middleware = ToolSearchAgentMiddleware.of(
            ToolSearchTool.builder().addTool(hidden).build());
        Agent agent = Agent.builder("protected-search-agent")
            .chatModel(new QueueChatModel())
            .middleware(middleware)
            .build();
        AgentTurn turn = new AgentRunner().start(agent, "input");

        assertNotNull(agent.resolveTool(turn, ToolSearchTool.NAME));
        assertNull(agent.resolveTool(turn, "hiddenTool"));
    }

    private static AiMessage toolCalls(ToolCall... calls) {
        AiMessage message = new AiMessage();
        message.setToolCalls(Arrays.asList(calls));
        return message;
    }

    private static AiMessageResponse response(Prompt prompt, AiMessage message) {
        ChatContext context = new ChatContext();
        context.setPrompt(prompt);
        return new AiMessageResponse(context, null, message);
    }

    private static final class QueueChatModel implements ChatModel {
        private final Deque<Function<Prompt, AiMessage>> responses = new ArrayDeque<>();
        private int callCount;

        private void enqueue(Function<Prompt, AiMessage> response) {
            responses.add(response);
        }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            callCount++;
            assertFalse("No queued response for model call " + callCount, responses.isEmpty());
            return response(prompt, responses.removeFirst().apply(prompt));
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener,
                               ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}

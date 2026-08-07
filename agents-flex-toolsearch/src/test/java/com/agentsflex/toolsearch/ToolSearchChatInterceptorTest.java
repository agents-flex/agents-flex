package com.agentsflex.toolsearch;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatContextHolder;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.model.client.ChatRequestSpecBuilder;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** 普通 ChatModel 使用 ToolSearchChatInterceptor 的渐进披露契约测试。 */
public class ToolSearchChatInterceptorTest {

    @Test
    public void shouldExecuteSearchAndDiscoveredToolThroughBaseChatModel() {
        Tool weather = Tool.builder("weatherLookup", args -> "sunny")
            .description("Weather forecast").build();
        ToolSearchTool searchTool = ToolSearchTool.builder().addTool(weather).build();
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addTool(searchTool);
        Deque<AiMessage> responses = new ArrayDeque<>();
        AiMessage searchCall = new AiMessage();
        searchCall.setToolCalls(Collections.singletonList(
            new ToolCall("search-1", searchTool.getName(),
                "{\"query\":\"weather forecast\"}")));
        responses.add(searchCall);
        AiMessage weatherCall = new AiMessage();
        weatherCall.setToolCalls(Collections.singletonList(
            new ToolCall("weather-1", "weatherLookup", "{}")));
        responses.add(weatherCall);
        AtomicReference<Prompt> requestPrompt = new AtomicReference<>();
        BaseChatModel<BaseChatConfig> model = model(responses, requestPrompt);
        model.addInterceptor(new ToolSearchChatInterceptor());

        AiMessageResponse searchResponse = model.chat(prompt);
        prompt.addMessage(searchResponse.getMessage());
        prompt.addMessages(searchResponse.executeToolCallsAndGetToolMessages());
        AiMessageResponse weatherResponse = model.chat(prompt);

        assertSame(weather, requestPrompt.get().getToolsMap().get("weatherLookup"));
        assertEquals(Collections.singletonList("sunny"),
            weatherResponse.executeToolCallsAndGetResults());
        assertNull(prompt.getToolsMap().get("weatherLookup"));
    }

    @Test
    public void shouldExposeLatestSearchResultWithoutMutatingOriginalPrompt() {
        Tool weather = Tool.builder("weatherLookup", args -> "sunny")
            .description("Weather forecast").build();
        Tool email = Tool.builder("sendEmail", args -> "sent")
            .description("Email delivery").build();
        ToolSearchTool searchTool = ToolSearchTool.builder()
            .addTools(Arrays.asList(weather, email))
            .build();
        Tool alwaysVisible = Tool.builder("currentTime", args -> "12:00").build();
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addTool(alwaysVisible);
        prompt.addTool(searchTool);
        addSearchResult(prompt, searchTool.getName(), "search-1", "[\"weatherLookup\"]");

        Prompt firstResolved = resolve(prompt);

        assertNotSame(prompt, firstResolved);
        assertSame(alwaysVisible, firstResolved.getToolsMap().get("currentTime"));
        assertSame(searchTool, firstResolved.getToolsMap().get(searchTool.getName()));
        assertSame(weather, firstResolved.getToolsMap().get("weatherLookup"));
        assertNull(firstResolved.getToolsMap().get("sendEmail"));
        assertNull(prompt.getToolsMap().get("weatherLookup"));

        addSearchResult(prompt, searchTool.getName(), "search-2", "[\"sendEmail\"]");
        Prompt secondResolved = resolve(prompt);

        assertNull(secondResolved.getToolsMap().get("weatherLookup"));
        assertSame(email, secondResolved.getToolsMap().get("sendEmail"));
        assertEquals(2, prompt.getTools().size());
    }

    @Test
    public void shouldClearPreviousResultWhenLatestSearchIsUnfinishedOrInvalid() {
        Tool weather = Tool.builder("weatherLookup", args -> "sunny")
            .description("Weather forecast").build();
        ToolSearchTool searchTool = ToolSearchTool.builder().addTool(weather).build();
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addTool(searchTool);
        addSearchResult(prompt, searchTool.getName(), "search-1", "[\"weatherLookup\"]");
        AiMessage unfinished = new AiMessage();
        unfinished.setToolCalls(Collections.singletonList(
            new ToolCall("search-2", searchTool.getName(), "{}")));
        prompt.addMessage(unfinished);

        assertNull(resolve(prompt).getToolsMap().get("weatherLookup"));

        ToolMessage invalid = new ToolMessage();
        invalid.setToolCallId("search-2");
        invalid.setContent("search failed");
        prompt.addMessage(invalid);

        assertNull(resolve(prompt).getToolsMap().get("weatherLookup"));
    }

    @Test
    public void shouldResolvePromptForStreamingCalls() {
        Tool weather = Tool.builder("weatherLookup", args -> "sunny").build();
        ToolSearchTool searchTool = ToolSearchTool.builder().addTool(weather).build();
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addTool(searchTool);
        addSearchResult(prompt, searchTool.getName(), "search-1", "[\"weatherLookup\"]");
        ChatContext context = new ChatContext();
        context.setPrompt(prompt);
        AtomicReference<Prompt> resolved = new AtomicReference<>();

        new ToolSearchChatInterceptor().interceptStream(null, context, (ctx, response) -> {
        }, (model, current, listener) -> resolved.set(current.getPrompt()));

        assertNotNull(resolved.get());
        assertSame(weather, resolved.get().getToolsMap().get("weatherLookup"));
    }

    private Prompt resolve(Prompt prompt) {
        ChatContext context = new ChatContext();
        context.setPrompt(prompt);
        AiMessageResponse response = new ToolSearchChatInterceptor().intercept(
            null, context, (model, current) ->
                new AiMessageResponse(current, null, new AiMessage("ok")));
        return response.getContext().getPrompt();
    }

    private void addSearchResult(MemoryPrompt prompt, String toolName,
                                 String callId, String result) {
        AiMessage searchCall = new AiMessage();
        searchCall.setToolCalls(Collections.singletonList(
            new ToolCall(callId, toolName, "{}")));
        ToolMessage toolMessage = new ToolMessage();
        toolMessage.setToolCallId(callId);
        toolMessage.setContent(result);
        prompt.addMessage(searchCall);
        prompt.addMessage(toolMessage);
    }

    private BaseChatModel<BaseChatConfig> model(Deque<AiMessage> responses,
                                                 AtomicReference<Prompt> requestPrompt) {
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(
            new BaseChatConfig()) {
        };
        model.setChatRequestSpecBuilder(new ChatRequestSpecBuilder() {
            @Override
            public ChatRequestSpec buildRequestSpec(Prompt prompt,
                                                     com.agentsflex.core.model.chat.ChatOptions options,
                                                     BaseChatConfig config) {
                return new ChatRequestSpec("test", Collections.emptyMap(), 0, 0);
            }

            @Override
            public String buildRequestBody(Prompt prompt,
                                           com.agentsflex.core.model.chat.ChatOptions options,
                                           BaseChatConfig config) {
                requestPrompt.set(prompt);
                return "{}";
            }
        });
        model.setChatClient(new ChatClient(model) {
            @Override
            public AiMessageResponse chat(String body) {
                return new AiMessageResponse(ChatContextHolder.currentContext(),
                    body, responses.removeFirst());
            }

            @Override
            public void chatStream(String body,
                                   com.agentsflex.core.model.chat.StreamResponseListener listener) {
            }
        });
        return model;
    }
}

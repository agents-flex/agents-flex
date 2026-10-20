package com.agentsflex.core.model.chat;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.client.BaseStreamClientListener;
import com.agentsflex.core.model.client.OpenAIChatRequestSpecBuilder;
import com.agentsflex.core.prompt.SimplePrompt;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ThinkingProtocolTest {
    private final OpenAIChatRequestSpecBuilder builder = new OpenAIChatRequestSpecBuilder();

    @Test
    public void inheritsConfigPerFieldAndPreservesServerDefault() {
        BaseChatConfig config = config("qwen");
        assertFalse(body(config, null).containsKey("enable_thinking"));
        config.setThinkingEnabled(true);
        assertTrue(body(config, null).getBooleanValue("enable_thinking"));
        ChatOptions temperatureOnly = ChatOptions.builder().temperature(0.2f).build();
        assertTrue(body(config, temperatureOnly).getBooleanValue("enable_thinking"));
        assertFalse(body(config, ChatOptions.builder().thinkingEnabled(false).build())
            .getBooleanValue("enable_thinking"));
        assertNull(temperatureOnly.getThinkingEnabled());
        assertEquals(Boolean.TRUE, config.getThinkingEnabled());
        config.setThinkingEnabled(null);
        assertFalse(body(config, temperatureOnly).containsKey("enable_thinking"));
    }

    @Test
    public void serializesBuiltInProtocolsAndRejectsInvalidOverrides() {
        ChatOptions enabled = ChatOptions.builder().thinkingEnabled(true).build();
        ChatOptions disabled = ChatOptions.builder().thinkingEnabled(false).build();
        assertEquals("enabled", body(config("deepseek"), enabled).getJSONObject("thinking").getString("type"));
        assertEquals("disabled", body(config("deepseek"), disabled).getJSONObject("thinking").getString("type"));
        assertEquals("medium", body(config("openai"), enabled).getString("reasoning_effort"));
        assertEquals("none", body(config("ollama"), disabled).getString("reasoning_effort"));
        assertEquals(Boolean.TRUE, body(config("ollama-native"), enabled).getBoolean("think"));
        assertThrows(IllegalArgumentException.class, () -> body(config("none"), enabled));
        assertThrows(IllegalArgumentException.class, () -> body(config("typo"), enabled));
        BaseChatConfig unsupported = config("qwen");
        unsupported.setSupportThinking(false);
        assertThrows(IllegalArgumentException.class, () -> body(unsupported, enabled));
        assertFalse(body(unsupported, null).containsKey("enable_thinking"));
    }

    @Test
    public void preservesToolReasoningForDefaultModeWithoutVendorPreserveParameter() {
        BaseChatConfig config = config("deepseek");
        SimplePrompt prompt = toolPrompt();
        JSONObject result = JSON.parseObject(builder.buildRequestBody(prompt, null, config));
        assertEquals("look up weather", result.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"));
        assertFalse(result.containsKey("preserve_thinking"));
        assertFalse(result.containsKey("thinking"));
        ChatOptions disabled = ChatOptions.builder().thinkingEnabled(false).build();
        result = JSON.parseObject(builder.buildRequestBody(prompt, disabled, config));
        assertFalse(result.getJSONArray("messages").getJSONObject(1).containsKey("reasoning_content"));
        config.setPreserveThinkingEnable(true);
        result = JSON.parseObject(builder.buildRequestBody(prompt, disabled, config));
        assertEquals("look up weather", result.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"));
        assertFalse(result.containsKey("preserve_thinking"));
        config.setPreserveThinkingEnable(false);
        result = JSON.parseObject(builder.buildRequestBody(prompt, null, config));
        assertFalse(result.getJSONArray("messages").getJSONObject(1).containsKey("reasoning_content"));
    }

    @Test
    public void streamFinalReasoningSurvivesSerializationAndToolContinuation() {
        AtomicReference<AiMessage> completed = new AtomicReference<>();
        BaseStreamClientListener listener = new BaseStreamClientListener(null, new ChatContext(), null,
            (context, response) -> {
                if (response.getMessage().isFinalDelta()) completed.set(response.getMessage());
            }, (json, context) -> {
            AiMessage delta = new AiMessage();
            delta.setReasoningContent(json.getString("reasoning"));
            if (json.getBooleanValue("tool")) delta.setToolCalls(toolPrompt().getMessages().stream()
                .filter(message -> message instanceof AiMessage)
                .map(message -> ((AiMessage) message).getToolCalls()).findFirst().get());
            return delta;
        });
        listener.onMessage(null, "{\"reasoning\":\"look up \"}");
        listener.onMessage(null, "{\"reasoning\":\"weather\",\"tool\":true}");
        listener.onMessage(null, "[DONE]");
        assertNotNull(completed.get());
        assertNull(completed.get().getReasoningContent());
        assertEquals("look up weather", completed.get().resolveReasoningText());
        AiMessage restored = JSON.parseObject(JSON.toJSONString(completed.get()), AiMessage.class);
        SimplePrompt next = new SimplePrompt("weather?");
        next.setAiMessage(restored);
        JSONObject result = JSON.parseObject(builder.buildRequestBody(next, null, config("deepseek")));
        assertEquals("look up weather", result.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"));
    }

    @Test
    public void requestOverridesDoNotLeakAcrossConcurrentCalls() {
        BaseChatConfig config = config("deepseek");
        config.setThinkingEnabled(true);
        CompletableFuture<JSONObject> on = CompletableFuture.supplyAsync(() -> body(config, new ChatOptions()));
        CompletableFuture<JSONObject> off = CompletableFuture.supplyAsync(() -> body(config,
            ChatOptions.builder().thinkingEnabled(false).build()));
        assertEquals("enabled", on.join().getJSONObject("thinking").getString("type"));
        assertEquals("disabled", off.join().getJSONObject("thinking").getString("type"));
        assertEquals(Boolean.TRUE, config.getThinkingEnabled());
    }

    @Test
    public void supportsCustomProtocolsAndRejectsConflictingExtraBody() {
        BaseChatConfig custom = config("none");
        custom.setThinkingProtocolHandler((enabled, body) -> {
            if (enabled != null) body.put("custom_thinking", enabled);
        });
        assertTrue(body(custom, ChatOptions.builder().thinkingEnabled(true).build()).getBooleanValue("custom_thinking"));
        ChatOptions options = ChatOptions.builder().thinkingEnabled(true).build();
        options.setExtraBody(Collections.singletonMap("enable_thinking", false));
        assertThrows(IllegalArgumentException.class, () -> body(config("qwen"), options));
        options.setExtraBody(Collections.singletonMap("other", "retained"));
        assertEquals("retained", body(config("qwen"), options).getString("other"));
    }

    private JSONObject body(BaseChatConfig config, ChatOptions options) {
        return JSON.parseObject(builder.buildRequestBody(new SimplePrompt("hello"), options, config));
    }

    private BaseChatConfig config(String protocol) {
        BaseChatConfig config = new BaseChatConfig();
        config.setThinkingProtocol(protocol);
        return config;
    }

    private SimplePrompt toolPrompt() {
        SimplePrompt prompt = new SimplePrompt("weather?");
        AiMessage message = new AiMessage();
        message.setFullReasoningContent("look up weather");
        ToolCall call = new ToolCall();
        call.setId("call-1");
        call.setName("weather");
        call.setArguments("{}");
        message.setToolCalls(Collections.singletonList(call));
        prompt.setAiMessage(message);
        return prompt;
    }
}

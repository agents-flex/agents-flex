package com.agentsflex.core.model.chat;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.model.client.ChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChatInterceptorRequestPreparationTest {

    @Test
    public void shouldBuildSyncRequestFromInterceptorModifiedContext() {
        AtomicReference<Prompt> builtPrompt = new AtomicReference<>();
        AtomicReference<ChatOptions> builtOptions = new AtomicReference<>();
        AtomicReference<BaseChatConfig> builtConfig = new AtomicReference<>();
        AtomicReference<ChatContext> clientContext = new AtomicReference<>();
        BaseChatConfig replacementConfig = new BaseChatConfig();
        replacementConfig.setModel("config-model");

        ChatInterceptor interceptor = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
                context.setPrompt(new SimplePrompt("rewritten prompt"));
                context.setOptions(ChatOptions.builder()
                    .model("interceptor-model")
                    .temperature(0.2f)
                    .contextBotId("bot-2")
                    .addContextAttributes("tenant", "tenant-3")
                    .build());
                context.setConfig(replacementConfig);
                assertNotNull(context.getRequestSpec());
                context.getRequestSpec().addHeader("X-Interceptor", "enabled");
                return chain.proceed(chatModel, context);
            }
        };
        BaseChatModel<BaseChatConfig> model = model(interceptor, builtPrompt, builtOptions, builtConfig, clientContext);

        model.chat(new SimplePrompt("original prompt"), new ChatOptions());

        assertEquals("rewritten prompt", builtPrompt.get().getMessages().get(0).getTextContent());
        assertEquals("interceptor-model", builtOptions.get().getModel());
        assertEquals(Float.valueOf(0.2f), builtOptions.get().getTemperature());
        assertFalse(builtOptions.get().isStreaming());
        assertSame(replacementConfig, builtConfig.get());
        assertEquals("bot-2", clientContext.get().getBotId());
        assertEquals("tenant-3", clientContext.get().getAttribute("tenant"));
        assertEquals("enabled", clientContext.get().getRequestSpec().getHeaders().get("X-Interceptor"));
    }

    @Test
    public void shouldForceStreamingAfterInterceptorReplacesOptions() {
        AtomicReference<Prompt> builtPrompt = new AtomicReference<>();
        AtomicReference<ChatOptions> builtOptions = new AtomicReference<>();
        AtomicReference<BaseChatConfig> builtConfig = new AtomicReference<>();
        AtomicReference<ChatContext> clientContext = new AtomicReference<>();
        ChatInterceptor interceptor = new ChatInterceptor() {
            @Override
            public void interceptStream(BaseChatModel<?> chatModel, ChatContext context,
                                        StreamResponseListener listener, StreamChain chain) {
                assertTrue(context.getOptions().isStreaming());
                context.setOptions(ChatOptions.builder().model("stream-model").build());
                chain.proceed(chatModel, context, listener);
            }
        };
        BaseChatModel<BaseChatConfig> model = model(interceptor, builtPrompt, builtOptions, builtConfig, clientContext);

        model.chatStream(new SimplePrompt("stream prompt"), (context, response) -> {
        }, new ChatOptions());

        assertTrue(builtOptions.get().isStreaming());
        assertEquals("stream-model", builtOptions.get().getModel());
        assertTrue(clientContext.get().getOptions().isStreaming());
        assertNotNull(clientContext.get().getRequestSpec());
    }

    private static BaseChatModel<BaseChatConfig> model(
        ChatInterceptor interceptor,
        AtomicReference<Prompt> builtPrompt,
        AtomicReference<ChatOptions> builtOptions,
        AtomicReference<BaseChatConfig> builtConfig,
        AtomicReference<ChatContext> clientContext) {
        BaseChatConfig config = new BaseChatConfig();
        config.setObservabilityEnabled(false);
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(
            config, Collections.singletonList(interceptor)) {
        };
        model.setChatRequestSpecBuilder(new ChatRequestSpecBuilder() {
            @Override
            public ChatRequestSpec buildRequestSpec(Prompt prompt, ChatOptions options, BaseChatConfig chatConfig) {
                return new ChatRequestSpec("test", new java.util.HashMap<>(), 0, 0);
            }

            @Override
            public String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig chatConfig) {
                builtPrompt.set(prompt);
                builtOptions.set(options);
                builtConfig.set(chatConfig);
                return "{}";
            }
        });
        model.setChatClient(new ChatClient(model) {
            @Override
            public AiMessageResponse chat(String body) {
                assertEquals("{}", body);
                ChatContext context = ChatContextHolder.currentContext();
                clientContext.set(context);
                return new AiMessageResponse(context, "{}", new AiMessage("ok"));
            }

            @Override
            public void chatStream(String body, StreamResponseListener listener) {
                assertEquals("{}", body);
                clientContext.set(ChatContextHolder.currentContext());
            }
        });
        return model;
    }
}

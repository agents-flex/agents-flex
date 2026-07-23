package com.agentsflex.core.model.chat;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolGroup;
import com.agentsflex.core.model.chat.tool.ToolGroupMatchers;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.model.client.ChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class ToolGroupChatIntegrationTest {

    @Test
    public void shouldExposeResolvedPromptToInterceptorsAndToolExecutionContext() {
        BaseChatConfig config = new BaseChatConfig();
        AtomicReference<Prompt> interceptorPrompt = new AtomicReference<>();
        ChatInterceptor interceptor = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
                ((SimplePrompt) context.getPrompt()).getUserMessage().setContent("weather tomorrow");
                context.getOptions().setModel("interceptor-model");
                context.getOptions().setContextAccountId("account-1");
                context.getOptions().setContextAttributes(Collections.singletonMap("plan", "premium"));
                interceptorPrompt.set(context.getPrompt());
                return chain.proceed(chatModel, context);
            }

            @Override
            public void interceptStream(BaseChatModel<?> chatModel, ChatContext context,
                                        StreamResponseListener listener, StreamChain chain) {
                chain.proceed(chatModel, context, listener);
            }
        };
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(config) {
        };
        AtomicReference<Prompt> serializedPrompt = new AtomicReference<>();
        model.setChatRequestSpecBuilder(new ChatRequestSpecBuilder() {
            @Override
            public ChatRequestSpec buildRequest(Prompt prompt, ChatOptions options, BaseChatConfig chatConfig) {
                return new ChatRequestSpec("test", Collections.emptyMap(), 0, 0);
            }

            @Override
            public String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig chatConfig) {
                serializedPrompt.set(prompt);
                return "{}";
            }
        });
        model.setChatClient(new ChatClient(model) {
            @Override
            public AiMessageResponse chat(String body) {
                ChatContext context = ChatContextHolder.currentContext();
                return new AiMessageResponse(context, "{}", new AiMessage("ok"));
            }

            @Override
            public void chatStream(String body, StreamResponseListener listener) {
            }
        });
        model.addInterceptor(interceptor);

        SimplePrompt original = new SimplePrompt("hello");
        original.addToolGroup(ToolGroup.builder("weather")
            .addTool(Tool.builder("weather", args -> "sunny").build())
            .matcher(context -> {
                assertEquals("interceptor-model", context.getOptions().getModel());
                assertEquals("account-1", context.getAccountId());
                assertEquals("premium", context.getAttribute("plan"));
                return ToolGroupMatchers.promptContains("weather").matches(context);
            })
            .build());

        ChatOptions options = ChatOptions.builder()
            .model("initial-model")
            .contextAccountId("account-0")
            .build();
        AiMessageResponse response = model.chat(original, options);

        assertNotSame(original, serializedPrompt.get());
        assertSame(original, interceptorPrompt.get());
        assertSame(serializedPrompt.get(), response.getContext().getPrompt());
        assertEquals("weather", response.getContext().getPrompt().getTools().get(0).getName());
        assertEquals(0, original.getTools() == null ? 0 : original.getTools().size());
    }
}

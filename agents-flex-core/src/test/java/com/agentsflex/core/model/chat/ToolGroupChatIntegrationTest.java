package com.agentsflex.core.model.chat;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolGroup;
import com.agentsflex.core.model.chat.tool.ToolGroupMatchers;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.model.client.ChatRequestSpec;
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
                interceptorPrompt.set(context.getPrompt());
                return chain.proceed(chatModel, context);
            }

            @Override
            public void interceptStream(BaseChatModel<?> chatModel, ChatContext context,
                                        StreamResponseListener listener, StreamChain chain) {
                chain.proceed(chatModel, context, listener);
            }
        };
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(
            config, Collections.singletonList(interceptor)) {
        };
        AtomicReference<Prompt> serializedPrompt = new AtomicReference<>();
        model.setChatRequestSpecBuilder((prompt, options, chatConfig) -> {
            serializedPrompt.set(prompt);
            return new ChatRequestSpec("test", Collections.emptyMap(), "{}", 0, 0);
        });
        model.setChatClient(new ChatClient(model) {
            @Override
            public AiMessageResponse chat() {
                ChatContext context = ChatContextHolder.currentContext();
                return new AiMessageResponse(context, "{}", new AiMessage("ok"));
            }

            @Override
            public void chatStream(StreamResponseListener listener) {
            }
        });

        SimplePrompt original = new SimplePrompt("weather tomorrow");
        original.addToolGroup(ToolGroup.builder("weather")
            .addTool(Tool.builder("weather", args -> "sunny").build())
            .matcher(ToolGroupMatchers.promptContains("weather"))
            .build());

        AiMessageResponse response = model.chat(original);

        assertNotSame(original, serializedPrompt.get());
        assertSame(serializedPrompt.get(), interceptorPrompt.get());
        assertSame(serializedPrompt.get(), response.getContext().getPrompt());
        assertEquals("weather", response.getContext().getPrompt().getTools().get(0).getName());
        assertEquals(0, original.getTools() == null ? 0 : original.getTools().size());
    }
}

package com.agentsflex.core.model.chat;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.model.client.ChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ChatInterceptorRegistrationTest {

    @After
    public void clearGlobalInterceptors() {
        GlobalChatInterceptors.clear();
    }

    @Test
    public void shouldMatchAgainstContextModifiedByEarlierInterceptor() {
        AtomicInteger invocations = new AtomicInteger();
        ChatInterceptor contextEnricher = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
                context.addAttribute("feature", "enabled");
                return chain.proceed(chatModel, context);
            }
        };
        BaseChatModel<BaseChatConfig> model = model(Collections.singletonList(contextEnricher));
        ChatInterceptor conditional = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
                invocations.incrementAndGet();
                return chain.proceed(chatModel, context);
            }
        };

        model.addInterceptorRegistration(ChatInterceptorRegistration.builder("conditional", conditional)
            .matcher(context -> "enabled".equals(context.getAttribute("feature")))
            .build());

        model.chat(new SimplePrompt("hello"));

        assertEquals(1, invocations.get());
        assertSame(conditional, model.getInterceptors().get(1));
        assertEquals("conditional", model.getInterceptorRegistrations().get(1).getName());
    }

    @Test
    public void shouldSkipUnmatchedRegistrationForSyncAndStreamRequests() {
        AtomicInteger invocations = new AtomicInteger();
        ChatInterceptor conditional = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
                invocations.incrementAndGet();
                return chain.proceed(chatModel, context);
            }

            @Override
            public void interceptStream(BaseChatModel<?> chatModel, ChatContext context,
                                        StreamResponseListener listener, StreamChain chain) {
                invocations.incrementAndGet();
                chain.proceed(chatModel, context, listener);
            }
        };
        BaseChatModel<BaseChatConfig> model = model(Collections.emptyList());
        model.addInterceptorRegistration(ChatInterceptorRegistration.builder("never", conditional)
            .matcher(context -> false)
            .build());

        model.chat(new SimplePrompt("sync"));
        model.chatStream(new SimplePrompt("stream"), (context, response) -> {
        });

        assertEquals(0, invocations.get());
    }

    @Test
    public void shouldExecuteRegistrationsInAscendingOrder() {
        StringBuilder executionOrder = new StringBuilder();
        BaseChatModel<BaseChatConfig> model = model(Collections.emptyList());
        model.addInterceptorRegistration(ChatInterceptorRegistration.builder("later", new ChatInterceptor() {
                @Override
                public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
                    executionOrder.append('A');
                    return chain.proceed(chatModel, context);
                }
            })
            .order(100)
            .build());
        model.addInterceptorRegistration(ChatInterceptorRegistration.builder("earlier", new ChatInterceptor() {
                @Override
                public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
                    executionOrder.append('B');
                    return chain.proceed(chatModel, context);
                }
            })
            .order(-100)
            .build());

        model.chat(new SimplePrompt("ordered"));

        assertEquals("BA", executionOrder.toString());
    }

    @Test
    public void shouldStoreGlobalRegistrationsAndReturnSnapshots() {
        ChatInterceptor first = new ChatInterceptor() {
        };
        ChatInterceptorRegistration registration = ChatInterceptorRegistration.builder("first", first)
            .matcher(context -> true)
            .build();
        GlobalChatInterceptors.addRegistration(registration);

        List<ChatInterceptorRegistration> snapshot = GlobalChatInterceptors.getRegistrations();
        GlobalChatInterceptors.addInterceptor(new ChatInterceptor() {
        });

        assertEquals(1, snapshot.size());
        assertSame(registration, snapshot.get(0));
        assertEquals(2, GlobalChatInterceptors.size());
        assertSame(first, GlobalChatInterceptors.getInterceptors().get(0));
    }

    @Test
    public void shouldDefineOrderedFrameworkRegistrationsWithoutExposingThemAsApplicationRegistrations() {
        List<ChatInterceptorRegistration> framework = FrameworkChatInterceptors.getRegistrations();

        assertEquals(1, framework.size());
        assertEquals("chat-observability", framework.get(0).getName());
        assertEquals(ChatInterceptorOrders.OBSERVABILITY, framework.get(0).getOrder());

        BaseChatModel<BaseChatConfig> model = model(Collections.emptyList());
        assertEquals(0, model.getInterceptorRegistrations().size());
    }

    @Test
    public void shouldApplyToolProvidedInterceptorOncePerRequest() {
        AtomicInteger invocations = new AtomicInteger();
        ChatInterceptor interceptor = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context,
                                               SyncChain chain) {
                invocations.incrementAndGet();
                return chain.proceed(chatModel, context);
            }
        };
        Tool providerTool = new ProviderTool(interceptor);
        SimplePrompt prompt = new SimplePrompt("hello");
        prompt.addTool(providerTool);
        prompt.addTool(providerTool);
        BaseChatModel<BaseChatConfig> model = model(Collections.<ChatInterceptor>emptyList());

        model.chat(prompt);

        assertEquals(1, invocations.get());
        assertEquals(0, model.getInterceptors().size());
    }

    @Test
    public void shouldApplyProviderExplicitlyAddedToPrompt() {
        AtomicInteger invocations = new AtomicInteger();
        final ChatInterceptor interceptor = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context,
                                               SyncChain chain) {
                invocations.incrementAndGet();
                return chain.proceed(chatModel, context);
            }
        };
        SimplePrompt prompt = new SimplePrompt("hello");
        prompt.addChatInterceptorProvider(new ChatInterceptorProvider() {
            @Override
            public List<ChatInterceptor> getChatInterceptors() {
                return Collections.singletonList(interceptor);
            }
        });

        model(Collections.<ChatInterceptor>emptyList()).chat(prompt);

        assertEquals(1, invocations.get());
    }

    @Test
    public void shouldApplyRegistrationProvidedByPromptItselfWithItsOrder() {
        final StringBuilder order = new StringBuilder();
        ChatInterceptor modelInterceptor = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context,
                                               SyncChain chain) {
                order.append('M');
                return chain.proceed(chatModel, context);
            }
        };
        ChatInterceptor promptInterceptor = new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context,
                                               SyncChain chain) {
                order.append('P');
                return chain.proceed(chatModel, context);
            }
        };
        ProviderPrompt prompt = new ProviderPrompt(promptInterceptor);

        model(Collections.singletonList(modelInterceptor)).chat(prompt);

        assertEquals("PM", order.toString());
    }

    private static final class ProviderPrompt extends SimplePrompt
        implements ChatInterceptorProvider {
        private final ChatInterceptor interceptor;

        private ProviderPrompt(ChatInterceptor interceptor) {
            super("hello");
            this.interceptor = interceptor;
        }

        @Override
        public List<ChatInterceptorRegistration> getChatInterceptorRegistrations() {
            return Collections.singletonList(
                ChatInterceptorRegistration.builder("prompt-provider", interceptor)
                    .order(-100)
                    .build());
        }
    }

    private static final class ProviderTool implements Tool, ChatInterceptorProvider {
        private final ChatInterceptor interceptor;

        private ProviderTool(ChatInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        @Override
        public List<ChatInterceptor> getChatInterceptors() {
            return Collections.singletonList(interceptor);
        }

        @Override
        public String getName() {
            return "providerTool";
        }

        @Override
        public String getDescription() {
            return "test provider";
        }

        @Override
        public Parameter[] getParameters() {
            return new Parameter[0];
        }

        @Override
        public Object invoke(Map<String, Object> argsMap) {
            return null;
        }
    }

    private static BaseChatModel<BaseChatConfig> model(List<ChatInterceptor> interceptors) {
        BaseChatConfig config = new BaseChatConfig();
        config.setObservabilityEnabled(false);
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(config, interceptors) {
        };
        model.setChatRequestSpecBuilder(new ChatRequestSpecBuilder() {
            @Override
            public ChatRequestSpec buildRequestSpec(Prompt prompt, ChatOptions options, BaseChatConfig chatConfig) {
                return new ChatRequestSpec("test", Collections.emptyMap(), 0, 0);
            }

            @Override
            public String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig chatConfig) {
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
        return model;
    }
}

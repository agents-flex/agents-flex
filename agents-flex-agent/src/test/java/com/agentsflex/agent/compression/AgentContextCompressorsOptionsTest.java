package com.agentsflex.agent.compression;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentContextCompressorsOptionsTest {

    @Test
    public void modelCompressorUsesCustomPromptPrefixFormatterAndChatOptions() {
        CapturingChatModel model = new CapturingChatModel("summary");
        ChatOptions chatOptions = ChatOptions.builder()
            .temperature(0.1f)
            .maxTokens(256)
            .thinkingEnabled(false)
            .build();
        AgentContextModelCompressorOptions options =
            AgentContextModelCompressorOptions.builder()
                .instruction("summarize")
                .historyHeader("\nHISTORY\n")
                .summaryPrefix("CUSTOM PREFIX")
                .chatOptions(chatOptions)
                .modelMessageFormatter(message -> "item=" + message.getTextContent() + '\n')
                .build();

        List<Message> result = AgentContextCompressors.model(model, options)
            .compress(Arrays.<Message>asList(new UserMessage("question"), new AiMessage("answer")));

        assertEquals("summarize\nHISTORY\nitem=question\nitem=answer\n", model.promptText);
        assertEquals(Float.valueOf(0.1f), model.options.getTemperature());
        assertEquals(Integer.valueOf(256), model.options.getMaxTokens());
        assertEquals(Boolean.FALSE, model.options.getThinkingEnabled());
        assertNotSame(chatOptions, model.options);
        assertEquals("CUSTOM PREFIX", result.get(0).getTextContent());
        assertEquals("summary", result.get(1).getTextContent());
    }

    @Test
    public void perMessageCompressorUsesCustomRequestFormatterAndChatOptions() {
        UserMessage user = new UserMessage("long question");
        String response = "[{\"messageId\":\"" + user.getMessageId()
            + "\",\"summary\":\"short question\"}]";
        CapturingChatModel model = new CapturingChatModel(response);
        AgentContextModelCompressorOptions options =
            AgentContextModelCompressorOptions.builder()
                .instruction("compress")
                .perMessageRequest("\nJSON\n")
                .chatOptions(ChatOptions.builder().maxTokens(128).build())
                .perMessageFormatter(message -> "id=" + message.getMessageId()
                    + "; text=" + message.getTextContent() + '\n')
                .build();

        List<Message> result = AgentContextCompressors.perMessageModel(model, options)
            .compress(Arrays.<Message>asList(user));

        assertTrue(model.promptText.startsWith("compress\nJSON\nid=" + user.getMessageId()));
        assertEquals(Integer.valueOf(128), model.options.getMaxTokens());
        assertEquals("short question", result.get(0).getTextContent());
        assertEquals(user.getMessageId(), result.get(0).getMessageId());
    }

    @Test(expected = IllegalStateException.class)
    public void perMessageFormatterMustKeepMessageId() {
        CapturingChatModel model = new CapturingChatModel("[]");
        AgentContextModelCompressorOptions options =
            AgentContextModelCompressorOptions.builder()
                .instruction("compress")
                .perMessageFormatter(message -> message.getTextContent())
                .build();

        AgentContextCompressors.perMessageModel(model, options)
            .compress(Arrays.<Message>asList(new UserMessage("question")));
    }

    @Test
    public void perMessageCompressorSkipsModelWhenNoMessageCanBeCompressed() {
        CapturingChatModel model = new CapturingChatModel("[]");
        AiMessage toolCall = new AiMessage();
        toolCall.setToolCalls(Arrays.asList(new ToolCall("call-1", "lookup", "{}")));
        ToolMessage toolResult = new ToolMessage();
        toolResult.setToolCallId("call-1");
        toolResult.setContent("result");

        List<Message> result = AgentContextCompressors.perMessageModel(model, "compress")
            .compress(Arrays.<Message>asList(toolCall, toolResult));

        assertNull(model.promptText);
        assertEquals(2, result.size());
        assertNotSame(toolCall, result.get(0));
        assertNotSame(toolResult, result.get(1));
    }

    @Test(expected = IllegalStateException.class)
    public void perMessageCompressorRejectsNonObjectJsonItems() {
        CapturingChatModel model = new CapturingChatModel("[\"invalid\"]");

        AgentContextCompressors.perMessageModel(model, "compress")
            .compress(Arrays.<Message>asList(new UserMessage("question")));
    }

    @Test
    public void textExcerptUsesCustomPrefix() {
        List<Message> result = AgentContextCompressors.textExcerpt(100, "ARCHIVE")
            .compress(Arrays.<Message>asList(new UserMessage("question")));

        assertEquals("ARCHIVE\nquestion\n", result.get(0).getTextContent());
    }

    @Test
    public void optionsDefensivelyCopyChatOptions() {
        ChatOptions source = ChatOptions.builder().maxTokens(100).build();
        AgentContextModelCompressorOptions options =
            AgentContextModelCompressorOptions.builder()
                .instruction("summary")
                .chatOptions(source)
                .build();

        source.setMaxTokens(200);
        ChatOptions first = options.getChatOptions();
        first.setMaxTokens(300);

        assertEquals(Integer.valueOf(100), options.getChatOptions().getMaxTokens());
        assertNotSame(first, options.getChatOptions());
    }

    @Test
    public void compressionModelTimeoutIsApplied() {
        ChatModel slow = new CapturingChatModel("summary") {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                return super.chat(prompt, options);
            }
        };
        AgentContextModelCompressorOptions options = AgentContextModelCompressorOptions.builder()
            .instruction("summarize")
            .modelCallTimeoutMillis(20)
            .build();
        try {
            AgentContextCompressors.model(slow, options)
                .compress(Arrays.<Message>asList(new UserMessage("question")));
            fail("compression model timeout must fail promptly");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("timeout"));
        }
    }

    @Test
    public void modelCompressorOptionsRejectMissingRequiredValues() {
        assertInvalid(() -> AgentContextModelCompressorOptions.builder().build());
        assertInvalid(() -> AgentContextModelCompressorOptions.builder().instruction(" ").build());
        assertInvalid(() -> AgentContextModelCompressorOptions.builder()
            .instruction("ok").historyHeader(null).build());
        assertInvalid(() -> AgentContextModelCompressorOptions.builder()
            .instruction("ok").perMessageRequest(null).build());
        assertInvalid(() -> AgentContextModelCompressorOptions.builder()
            .instruction("ok").summaryPrefix(null).build());
        assertInvalid(() -> AgentContextModelCompressorOptions.builder()
            .instruction("ok").modelMessageFormatter(null).build());
        assertInvalid(() -> AgentContextModelCompressorOptions.builder()
            .instruction("ok").perMessageFormatter(null).build());
        assertInvalid(() -> AgentContextModelCompressorOptions.builder()
            .instruction("ok").modelCallTimeoutMillis(-1).build());
    }

    @Test
    public void compressorChainRejectsNullOutputWithExplicitError() {
        try {
            AgentContextCompressors.chain(messages -> null).compress(Collections.emptyList());
            fail("null compressor output must fail explicitly");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("returned null"));
        }
    }

    private static class CapturingChatModel implements ChatModel {
        private final String response;
        private String promptText;
        private ChatOptions options;

        private CapturingChatModel(String response) {
            this.response = response;
        }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            this.promptText = prompt.getMessages().get(0).getTextContent();
            this.options = options;
            return new AiMessageResponse(null, response, new AiMessage(response));
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener,
                               ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
            fail("invalid compressor option must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }
}

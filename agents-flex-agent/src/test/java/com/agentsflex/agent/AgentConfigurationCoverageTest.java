package com.agentsflex.agent;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.toolgroup.ToolGroup;
import com.agentsflex.core.model.chat.toolgroup.ToolGroupMatchers;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Agent 和 AgentRunner 配置的边界及组合契约测试。
 */
public class AgentConfigurationCoverageTest {

    @Test
    public void shouldSelectMultimodalModelOnlyForMultimodalPrompt() {
        RecordingModel text = new RecordingModel();
        RecordingModel multimodal = new RecordingModel();
        Agent agent = Agent.builder("routing")
            .chatModel(text)
            .multimodalChatModel(multimodal)
            .build();

        new AgentRunner().run(agent, "plain text");
        UserMessage image = new UserMessage("describe this image");
        image.addImageUrl("https://example.com/image.png");
        new AgentRunner().run(agent, image);

        assertEquals(1, text.calls.get());
        assertEquals(1, multimodal.calls.get());
    }

    @Test
    public void shouldFallbackToTextModelWhenMultimodalModelIsMissing() {
        RecordingModel text = new RecordingModel();
        Agent agent = Agent.builder("fallback").chatModel(text).build();
        UserMessage file = new UserMessage("inspect file");
        file.addFileUrl("https://example.com/report.pdf");
        new AgentRunner().run(agent, file);
        assertEquals(1, text.calls.get());
    }

    @Test
    public void shouldKeepAgentDefaultsAndCopyMutableConfiguration() {
        ChatOptions options = new ChatOptions();
        Agent agent = Agent.builder("defaults").chatModel(new RecordingModel())
            .chatOptions(options).attribute("env", "test").build();
        assertEquals(10, agent.getMaxAttachedTurns());
        assertEquals(100, agent.getMaxAttachedMessages());
        assertEquals(2, agent.getCompressionPolicy().getKeepRecentTurns());
        assertTrue(agent.getCompressionPolicy().isCompactCompletedToolTurns());
        assertSame(options, agent.getChatOptions());
        assertEquals("test", agent.getAttributes().get("env"));
        try {
            agent.getAttributes().put("x", "y");
            fail("attributes must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void shouldRegisterToolGroupAsConditionalModelCapabilityAndExecutableTool() {
        Tool weather = Tool.builder("query_weather", arguments -> "sunny").build();
        ToolGroup group = ToolGroup.builder("weather")
            .addTool(weather)
            .matcher(ToolGroupMatchers.promptContains("天气"))
            .build();
        Agent agent = Agent.builder("weather-agent")
            .chatModel(new RecordingModel())
            .toolGroup(group)
            .build();

        AgentTurn turn = AgentTurn.start(agent, new UserMessage("查询北京天气"));
        assertTrue(agent.getTools().isEmpty());
        assertEquals(1, agent.getExecutableTools().size());
        assertSame(weather, agent.resolveTool(turn, "query_weather"));
        assertTrue(turn.getPrompt().getTools().isEmpty());
        assertEquals(1, turn.getPrompt().getToolGroups().size());
        assertSame(group, turn.getPrompt().getToolGroups().get(0));
    }

    @Test
    public void shouldRejectDifferentToolInstancesWithSameNameAcrossToolGroupAndAgent() {
        Tool direct = Tool.builder("lookup", arguments -> "direct").build();
        Tool grouped = Tool.builder("lookup", arguments -> "grouped").build();
        ToolGroup group = ToolGroup.builder("lookup-group").addTool(grouped).build();
        assertIllegalState(() -> Agent.builder("invalid")
            .chatModel(new RecordingModel()).tool(direct).toolGroup(group).build());
    }

    @Test
    public void shouldRejectInvalidAgentAndRunnerBuilderArguments() {
        assertIllegalState(() -> Agent.builder().build());
        assertIllegalState(() -> Agent.builder().chatModel(new RecordingModel()).name(" ").build());
        assertIllegalState(() -> Agent.builder().chatModel(new RecordingModel()).version(" ").build());
        assertIllegalArgument(() -> Agent.builder().chatModel(new RecordingModel()).maxAttachedTurns(0));
        assertIllegalArgument(() -> Agent.builder().chatModel(new RecordingModel()).maxAttachedMessages(0));
        assertIllegalArgument(() -> Agent.builder().chatModel(new RecordingModel())
            .compressionPolicy(AgentContextCompressionPolicy.builder().keepRecentTurns(-1).build()));
        assertIllegalArgument(() -> AgentRunner.builder().turnStore(null).build());
        assertIllegalArgument(() -> AgentRunner.builder().agentLoader(null).build());
    }

    @Test
    public void shouldNotCompactOlderToolTurnWhenDisabled() {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addMessages(toolHistory());
        List<Message> messages = AgentContextWindow.build(prompt, 2, 100, false, 1, null)
            .getMemory().getMessages(Integer.MAX_VALUE);
        assertTrue(messages.stream().anyMatch(message -> message instanceof ToolMessage));
        assertTrue(messages.stream().anyMatch(message -> message instanceof AiMessage
            && ((AiMessage) message).hasToolCalls()));
    }

    @Test
    public void shouldPreserveRecentToolProtocolWhenMessageLimitIsTooSmall() {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addMessages(toolHistory());
        List<Message> messages = AgentContextWindow.build(prompt, 2, 1, true, 2, null)
            .getMemory().getMessages(Integer.MAX_VALUE);
        assertTrue(messages.size() >= 4);
        assertTrue(messages.get(0) instanceof UserMessage);
        assertTrue(messages.stream().anyMatch(message -> message instanceof ToolMessage));
    }

    @Test
    public void shouldSkipSemanticCompressionForIncompleteToolTurn() {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addMessages(Arrays.<Message>asList(
            new UserMessage("unfinished"), toolCall("pending"),
            new UserMessage("current"), new AiMessage("answer")));
        AtomicInteger compressorCalls = new AtomicInteger();
        AgentContextWindow.build(prompt, 2, 100, true, 0, messages -> {
            compressorCalls.incrementAndGet();
            return Arrays.<Message>asList(new UserMessage("summary"), new AiMessage("facts"));
        });
        assertEquals(0, compressorCalls.get());
    }

    @Test
    public void shouldCopyRequestOptionsWithoutMutatingAgentTemplate() {
        RecordingModel model = new RecordingModel();
        ChatOptions options = new ChatOptions();
        options.setContextAccountId("account");
        Agent agent = Agent.builder("options").chatModel(model).chatOptions(options).build();
        AgentTurn turn = new AgentRunner().run(agent, "hello");
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertNotSame(options, model.options);
        assertEquals("account", model.options.getContextAccountId());
        assertTrue(options.getContextTurnId() == null);
    }

    private static List<Message> toolHistory() {
        return Arrays.<Message>asList(
            new UserMessage("old request"), toolCall("old-call"), toolResult("old-call"),
            new AiMessage("old final"),
            new UserMessage("current request"), toolCall("current-call"), toolResult("current-call"),
            new AiMessage("current final"));
    }

    private static AiMessage toolCall(String id) {
        AiMessage message = new AiMessage();
        message.setToolCalls(Arrays.asList(new ToolCall(id, "lookup", "{}")));
        return message;
    }

    private static ToolMessage toolResult(String id) {
        ToolMessage message = new ToolMessage();
        message.setToolCallId(id);
        message.setContent("result");
        return message;
    }

    private static void assertIllegalState(Runnable action) {
        try {
            action.run();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static final class RecordingModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private ChatOptions options;

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            calls.incrementAndGet();
            this.options = options;
            ChatContext context = new ChatContext();
            context.setPrompt(prompt);
            return new AiMessageResponse(context, null, new AiMessage("done"));
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}

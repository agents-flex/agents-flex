package com.agentsflex.agent;

import com.agentsflex.agent.message.AgentActionMessage;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.prompt.MemoryPrompt;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class AgentContextWindowTest {

    @Test
    public void firstModelMessageIsUserAndOldToolTurnIsCompacted() {
        MemoryPrompt source = prompt(
            new UserMessage("old question"),
            aiTool("call-1"),
            tool("call-1", "old result"),
            new AiMessage("old final answer"),
            AgentActionMessage.toolApproval("turn", "action", "approval"),
            new UserMessage("current question"),
            new AiMessage("current answer"));

        MemoryPrompt result = AgentContextWindow.build(source, 2, 10, true);
        List<Message> messages = result.getMemory().getMessages(Integer.MAX_VALUE);

        Assert.assertEquals(4, messages.size());
        Assert.assertTrue(messages.get(0) instanceof UserMessage);
        Assert.assertEquals("old question", messages.get(0).getTextContent());
        Assert.assertEquals("old final answer", messages.get(1).getTextContent());
        Assert.assertTrue(messages.get(2) instanceof UserMessage);
        Assert.assertEquals("current question", messages.get(2).getTextContent());
        Assert.assertEquals("current answer", messages.get(3).getTextContent());
    }

    @Test
    public void currentTurnKeepsCompleteToolProtocolEvenWhenOverMessageLimit() {
        MemoryPrompt source = prompt(
            new UserMessage("current"),
            aiTool("call-1"),
            tool("call-1", "result"),
            aiTool("call-2"),
            tool("call-2", "result-2"),
            new AiMessage("done"));

        MemoryPrompt result = AgentContextWindow.build(source, 1, 2, true);
        List<Message> messages = result.getMemory().getMessages(Integer.MAX_VALUE);

        Assert.assertEquals(6, messages.size());
        Assert.assertTrue(messages.get(0) instanceof UserMessage);
        Assert.assertTrue(messages.get(1) instanceof AiMessage);
        Assert.assertTrue(messages.get(2) instanceof ToolMessage);
        Assert.assertTrue(messages.get(3) instanceof AiMessage);
        Assert.assertTrue(messages.get(4) instanceof ToolMessage);
        Assert.assertTrue(messages.get(5) instanceof AiMessage);
    }

    @Test
    public void maxAttachedTurnsDropsOlderCompleteTurnsWithoutSplittingRecentTurn() {
        MemoryPrompt source = prompt(
            new UserMessage("one"), new AiMessage("answer one"),
            new UserMessage("two"), new AiMessage("answer two"),
            new UserMessage("three"), new AiMessage("answer three"));

        MemoryPrompt result = AgentContextWindow.build(source, 2, 100, true);
        List<Message> messages = result.getMemory().getMessages(Integer.MAX_VALUE);

        Assert.assertEquals(4, messages.size());
        Assert.assertEquals("two", messages.get(0).getTextContent());
        Assert.assertEquals("answer two", messages.get(1).getTextContent());
        Assert.assertEquals("three", messages.get(2).getTextContent());
        Assert.assertEquals("answer three", messages.get(3).getTextContent());
    }

    @Test
    public void uiOnlyMessagesDoNotEnterModelWindow() {
        MemoryPrompt source = prompt(
            new UserMessage("question"),
            AgentActionMessage.toolApproval("turn", "action", "approve"),
            new AiMessage("answer"));

        MemoryPrompt result = AgentContextWindow.build(source, 1, 10, true);

        for (Message message : result.getMemory().getMessages(Integer.MAX_VALUE)) {
            Assert.assertFalse(message instanceof AgentActionMessage);
            Assert.assertTrue(message.isModelVisible());
        }
    }

    @Test
    public void systemMessageMayPrecedeUserButNoOtherMessageMayStartWindow() {
        MemoryPrompt source = prompt(new UserMessage("question"), new AiMessage("answer"));
        source.setSystemMessage("system instruction");

        MemoryPrompt result = AgentContextWindow.build(source, 1, 10, true);
        List<Message> messages = result.getMessages();

        Assert.assertEquals(3, messages.size());
        Assert.assertTrue(messages.get(0) instanceof com.agentsflex.core.message.SystemMessage);
        Assert.assertTrue(messages.get(1) instanceof UserMessage);
        Assert.assertTrue(messages.get(2) instanceof AiMessage);
    }

    @Test
    public void incompleteOldToolTurnIsNotIncorrectlyReduced() {
        MemoryPrompt source = prompt(
            new UserMessage("old"),
            aiTool("call-1"),
            tool("call-1", "partial"),
            new UserMessage("current"),
            new AiMessage("answer"));

        MemoryPrompt result = AgentContextWindow.build(source, 2, 100, true);
        List<Message> messages = result.getMemory().getMessages(Integer.MAX_VALUE);

        Assert.assertEquals(5, messages.size());
        Assert.assertTrue(messages.get(0) instanceof UserMessage);
        Assert.assertTrue(messages.get(1) instanceof AiMessage);
        Assert.assertTrue(((AiMessage) messages.get(1)).hasToolCalls());
        Assert.assertTrue(messages.get(2) instanceof ToolMessage);
    }

    @Test
    public void orphanAssistantOrToolMessagesBeforeFirstUserAreDropped() {
        MemoryPrompt source = prompt(
            new AiMessage("orphan assistant"),
            tool("orphan-call", "orphan tool"),
            new UserMessage("real question"),
            new AiMessage("real answer"));

        MemoryPrompt result = AgentContextWindow.build(source, 1, 10, true);
        List<Message> messages = result.getMemory().getMessages(Integer.MAX_VALUE);

        Assert.assertEquals(2, messages.size());
        Assert.assertTrue(messages.get(0) instanceof UserMessage);
        Assert.assertEquals("real question", messages.get(0).getTextContent());
    }

    @Test
    public void recentTurnsRemainCompleteAndCompressorOnlyReceivesOlderTurns() {
        MemoryPrompt source = prompt(
            new UserMessage("old"), new AiMessage("old answer"),
            new UserMessage("middle"), new AiMessage("middle answer"),
            new UserMessage("recent"), aiTool("recent-call"), tool("recent-call", "result"),
            new AiMessage("recent answer"),
            new UserMessage("current"), new AiMessage("current answer"));
        final List<Message> received = new java.util.ArrayList<>();
        MemoryPrompt result = AgentContextWindow.build(source, 10, 100, true, 2, messages -> {
            received.addAll(messages);
            return Arrays.asList(new UserMessage("compressed history"), new AiMessage("compressed answer"));
        });

        Assert.assertEquals(2, countUsers(received));
        List<Message> messages = result.getMemory().getMessages(Integer.MAX_VALUE);
        Assert.assertEquals("compressed history", messages.get(0).getTextContent());
        Assert.assertEquals("recent", messages.get(2).getTextContent());
        Assert.assertTrue(messages.get(3) instanceof AiMessage);
        Assert.assertEquals("current", messages.get(6).getTextContent());
        Assert.assertEquals(8, messages.size());
    }

    @Test
    public void invalidCompressorOutputIsRejected() {
        MemoryPrompt source = prompt(new UserMessage("old"), new AiMessage("answer"),
            new UserMessage("current"), new AiMessage("now"));
        try {
            AgentContextWindow.build(source, 10, 100, true, 0,
                messages -> Arrays.asList(new AiMessage("must start with user")));
            Assert.fail("expected invalid compressor output");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("SystemMessage"));
        }
    }

    @Test
    public void compressorMayReturnSystemMessageBeforeUserMessage() {
        MemoryPrompt source = prompt(new UserMessage("old"), new AiMessage("answer"),
            new UserMessage("current"), new AiMessage("now"));
        MemoryPrompt result = AgentContextWindow.build(source, 10, 100, true, 1,
            messages -> Arrays.asList(new SystemMessage("compressed policy"),
                new UserMessage("history summary"), new AiMessage("summary answer")));
        List<Message> messages = result.getMemory().getMessages(Integer.MAX_VALUE);
        Assert.assertTrue(messages.get(0) instanceof SystemMessage);
        Assert.assertTrue(messages.get(1) instanceof UserMessage);
    }

    @Test
    public void canNormalizeToolTurnsBeforeSemanticCompressionWhenEnabled() {
        MemoryPrompt source = prompt(
            new UserMessage("old request"), aiTool("call-1"), tool("call-1", "result"),
            new AiMessage("old final"),
            new UserMessage("current"), new AiMessage("now"));
        final List<Message> received = new java.util.ArrayList<>();
        AgentContextWindow.build(source, 2, 100, true, 1, messages -> {
            received.addAll(messages);
            return Arrays.asList(new UserMessage("summary"), new AiMessage("facts"));
        }, true);
        Assert.assertEquals(2, received.size());
        Assert.assertTrue(received.get(0) instanceof UserMessage);
        Assert.assertTrue(received.get(1) instanceof AiMessage);
        Assert.assertFalse(((AiMessage) received.get(1)).hasToolCalls());
    }

    @Test
    public void incrementalCompressorOnlyProcessesNewMessages() {
        final int[] calls = {0};
        AgentContextCompressors.Incremental compressor = AgentContextCompressors.incremental(messages -> {
            calls[0]++;
            return Arrays.asList(new UserMessage("summary-" + calls[0]), new AiMessage("facts"));
        });
        UserMessage first = new UserMessage("first");
        AiMessage answer = new AiMessage("answer");
        List<Message> history = Arrays.asList(first, answer);
        compressor.compress(history);
        compressor.compress(history);
        Assert.assertEquals(1, calls[0]);

        UserMessage next = new UserMessage("next");
        compressor.compress(Arrays.asList(first, answer, next));
        Assert.assertEquals(2, calls[0]);
        Assert.assertEquals(next.getMessageId(), compressor.getCoveredUntilMessageId());
    }

    @Test
    public void compressorToolMessageMustMatchPreviousToolCall() {
        MemoryPrompt source = prompt(new UserMessage("old"), new AiMessage("answer"),
            new UserMessage("current"), new AiMessage("now"));
        try {
            AgentContextWindow.build(source, 10, 100, true, 0,
                messages -> Arrays.asList(new UserMessage("summary"), aiTool("call-1"), tool("other", "bad")));
            Assert.fail("expected invalid tool pairing");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("orphan ToolMessage"));
        }
    }

    @Test
    public void maxMessagesDropsCompressedHistoryBeforeProtectedRecentTurns() {
        MemoryPrompt source = prompt(
            new UserMessage("old"), new AiMessage("old answer"),
            new UserMessage("recent"), new AiMessage("recent answer"),
            new UserMessage("current"), new AiMessage("current answer"));
        MemoryPrompt result = AgentContextWindow.build(source, 10, 4, true, 2,
            messages -> Arrays.asList(new UserMessage("summary"), new AiMessage("summary answer")));
        List<Message> messages = result.getMemory().getMessages(Integer.MAX_VALUE);
        Assert.assertEquals(4, messages.size());
        Assert.assertEquals("recent", messages.get(0).getTextContent());
        Assert.assertEquals("current", messages.get(2).getTextContent());
    }

    private static int countUsers(List<Message> messages) {
        int count = 0;
        for (Message message : messages) if (message instanceof UserMessage) count++;
        return count;
    }

    private static MemoryPrompt prompt(Message... messages) {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addMessages(Arrays.asList(messages));
        return prompt;
    }

    private static AiMessage aiTool(String id) {
        AiMessage message = new AiMessage();
        ToolCall call = new ToolCall();
        call.setId(id);
        call.setName("lookup");
        call.setArguments("{}");
        message.setToolCalls(Arrays.asList(call));
        return message;
    }

    private static ToolMessage tool(String id, String content) {
        ToolMessage message = new ToolMessage();
        message.setToolCallId(id);
        message.setContent(content);
        return message;
    }
}

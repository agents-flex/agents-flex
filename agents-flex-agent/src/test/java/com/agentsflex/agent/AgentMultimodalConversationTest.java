/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import org.junit.Test;

import java.util.List;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 验证 Agent 的多模态输入和跨 Run 持续对话契约。 */
public class AgentMultimodalConversationTest {

    @Test
    public void shouldPreserveMultimodalInputAcrossExecutionAndSnapshotRestore() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            UserMessage input = lastUserMessage(prompt.getMessages());
            assertMultimodalInput(input);
            return new AiMessage("analysis complete");
        });
        Agent agent = Agent.builder("multimodal-agent")
            .chatModel(model)
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));

        UserMessage input = multimodalMessage();
        AgentRun run = runner.run(agent, input);

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals("analysis complete", run.getFinalOutput());
        assertMultimodalInput(lastUserMessage(run.getConversationHistory()));
        assertMultimodalInput(lastUserMessage(runner.restore(run.getId()).getConversationHistory()));
    }

    @Test
    public void shouldDefensivelyCopyInputAndConversationHistory() {
        UserMessage previous = new UserMessage("previous question");
        previous.addImageUrl("https://example.com/previous.png");
        UserMessage current = multimodalMessage();
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("copy-agent")
            .chatModel(model)
            .build();

        AgentRun run = AgentRun.start(agent,
            java.util.Collections.<Message>singletonList(previous), current);
        previous.setContent("changed previous question");
        previous.addImageUrl("https://example.com/changed.png");
        current.setContent("changed current question");
        current.addAudioUrl("https://example.com/changed.mp3");

        List<Message> history = run.getConversationHistory();
        UserMessage storedPrevious = (UserMessage) history.get(0);
        UserMessage storedCurrent = (UserMessage) history.get(1);
        assertEquals("previous question", storedPrevious.getContent());
        assertEquals(1, storedPrevious.getImageUrls().size());
        assertEquals("inspect these resources", storedCurrent.getContent());
        assertEquals(1, storedCurrent.getAudioUrls().size());

        storedCurrent.addFileUrl("https://example.com/changed.txt");
        assertEquals(1, ((UserMessage) run.getConversationHistory().get(1)).getFileUrls().size());
    }

    @Test
    public void shouldCreateASeparateRunForEachConversationTurn() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("hello, how can I help?"));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals(4, messages.size());
            assertTrue(messages.get(0) instanceof SystemMessage);
            assertEquals("hello", messages.get(1).getTextContent());
            assertEquals("hello, how can I help?", messages.get(2).getTextContent());
            UserMessage current = (UserMessage) messages.get(3);
            assertEquals("what is shown here?", current.getContent());
            assertEquals("https://example.com/current.png", current.getImageUrls().get(0));
            return new AiMessage("a chart");
        });
        Agent agent = Agent.builder("conversation-agent")
            .instructions("answer briefly")
            .chatModel(model)
            .build();
        AgentRunner runner = new AgentRunner();
        AgentConversation conversation = AgentConversation.create("conversation-1", agent);

        AgentRun first = runner.run(conversation, new UserMessage("hello"));
        UserMessage secondInput = new UserMessage("what is shown here?");
        secondInput.addImageUrl("https://example.com/current.png");
        AgentRun second = runner.run(conversation, secondInput);

        assertNotEquals(first.getId(), second.getId());
        assertEquals(AgentRunStatus.COMPLETED, first.getStatus());
        assertEquals(AgentRunStatus.COMPLETED, second.getStatus());
        assertEquals("a chart", second.getFinalOutput());
        assertEquals(4, conversation.getMessages().size());
        assertFalse(conversation.getMessages().get(0) instanceof SystemMessage);
        assertEquals("conversation-1",
            second.getMetadata().get(AgentConversation.RUN_METADATA_KEY));
        assertNull(conversation.getActiveRunId());
    }

    @Test
    public void shouldResumeBlockedRunInsteadOfStartingAnotherConversationTurn() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals("initial request", messages.get(0).getTextContent());
            assertEquals("additional value", messages.get(1).getTextContent());
            return new AiMessage("completed after resume");
        });
        Agent agent = Agent.builder("waiting-conversation-agent")
            .chatModel(model)
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        AgentConversation conversation = AgentConversation.create("conversation-waiting", agent);
        AgentRun waiting = runner.start(conversation, "initial request");
        runner.suspend(waiting, AgentSuspension.userInput("provide value"));

        try {
            runner.run(conversation, "this must not start another run");
            throw new AssertionError("Expected active run rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("active run"));
        }

        AgentRun completed = runner.resume(conversation,
            AgentResumeCommand.userInput("additional value"));

        assertEquals(waiting.getId(), completed.getId());
        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("completed after resume", completed.getFinalOutput());
        assertEquals(3, conversation.getMessages().size());
        assertNull(conversation.getActiveRunId());
    }

    @Test
    public void shouldRebuildConversationHandleAndResumePersistedActiveRun() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("restored conversation completed"));
        Agent agent = Agent.builder("restored-conversation-agent")
            .chatModel(model)
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        DefaultChatMemory memory = new DefaultChatMemory("conversation-restored");
        AgentConversation original = AgentConversation.of(
            "conversation-restored", agent, memory);
        AgentRun waiting = runner.start(original, "initial request");
        runner.suspend(waiting, AgentSuspension.userInput("provide value"));

        AgentConversation rebuilt = AgentConversation.restore(
            "conversation-restored", agent, memory, waiting.getId());
        AgentRun completed = runner.resume(rebuilt,
            AgentResumeCommand.userInput("restored value"));

        assertEquals(waiting.getId(), completed.getId());
        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("restored conversation completed", completed.getFinalOutput());
        assertEquals(3, rebuilt.getMessages().size());
        assertNull(rebuilt.getActiveRunId());
    }

    @Test
    public void shouldKeepMultimodalInputDuringToolCallingLoop() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("call-1", "inspect", "{}")));
        model.enqueue(prompt -> {
            assertMultimodalInput(lastUserMessage(prompt.getMessages()));
            assertTrue(prompt.getMessages().get(prompt.getMessages().size() - 1) instanceof ToolMessage);
            return new AiMessage("tool-assisted answer");
        });
        Agent agent = Agent.builder("multimodal-tool-agent")
            .chatModel(model)
            .tool(tool("inspect", args -> "detected content"))
            .build();

        AgentRun run = new AgentRunner().run(agent, multimodalMessage());

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals(2, run.getIterationCount());
        assertEquals("tool-assisted answer", run.getFinalOutput());
    }

    private static UserMessage multimodalMessage() {
        UserMessage message = new UserMessage("inspect these resources");
        message.addImageUrl("https://example.com/image.png");
        message.addAudioUrl("https://example.com/audio.mp3");
        message.addVideoUrl("https://example.com/video.mp4");
        message.addFileUrl("https://example.com/document.pdf");
        message.putMetadata("source", "test");
        return message;
    }

    private static void assertMultimodalInput(UserMessage input) {
        assertEquals("inspect these resources", input.getContent());
        assertEquals("https://example.com/image.png", input.getImageUrls().get(0));
        assertEquals("https://example.com/audio.mp3", input.getAudioUrls().get(0));
        assertEquals("https://example.com/video.mp4", input.getVideoUrls().get(0));
        assertEquals("https://example.com/document.pdf", input.getFileUrls().get(0));
        assertEquals("test", input.getMetadata("source"));
    }

    private static UserMessage lastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return (UserMessage) messages.get(i);
            }
        }
        throw new AssertionError("UserMessage not found");
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.memory;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.prompt.MemoryPrompt;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class ChatMemoryMessageContractTest {

    @Test
    public void shouldKeepFullTimelineAndExposeOnlyModelMessages() {
        DefaultChatMemory memory = new DefaultChatMemory("conversation-1");
        memory.addMessage(new UserMessage("first"));
        UserMessage action = new UserMessage("approval card");
        action.setModelVisible(false);
        memory.addMessage(action);
        memory.addMessage(new UserMessage("second"));
        memory.addMessage(new UserMessage("third"));

        List<Message> timeline = memory.getMessages(Integer.MAX_VALUE);
        List<Message> modelMessages = memory.getModelMessages(2);

        assertEquals(4, timeline.size());
        assertEquals(2, modelMessages.size());
        assertEquals("second", modelMessages.get(0).getTextContent());
        assertEquals("third", modelMessages.get(1).getTextContent());
    }

    @Test
    public void shouldFilterUiMessagesBeforeMemoryPromptLimit() {
        DefaultChatMemory memory = new DefaultChatMemory();
        memory.addMessage(new UserMessage("old"));
        UserMessage action = new UserMessage("approval card");
        action.setModelVisible(false);
        memory.addMessage(action);
        memory.addMessage(new UserMessage("latest"));
        MemoryPrompt prompt = new MemoryPrompt(memory);
        prompt.setMaxAttachedMessageCount(2);

        List<Message> messages = prompt.getMessages();

        assertEquals(2, messages.size());
        assertEquals("old", messages.get(0).getTextContent());
        assertEquals("latest", messages.get(1).getTextContent());
    }

    @Test
    public void shouldReadTimelineUsingBackwardWindows() {
        DefaultChatMemory memory = new DefaultChatMemory();
        for (int index = 1; index <= 5; index++) {
            memory.addMessage(new UserMessage("message-" + index));
        }

        assertEquals("message-4", memory.getMessages(0, 2).get(0).getTextContent());
        assertEquals("message-5", memory.getMessages(0, 2).get(1).getTextContent());
        assertEquals("message-2", memory.getMessages(2, 2).get(0).getTextContent());
        assertEquals("message-3", memory.getMessages(2, 2).get(1).getTextContent());
        assertEquals("message-1", memory.getMessages(4, 2).get(0).getTextContent());
    }

    @Test
    public void shouldFilterModelMessagesAcrossBoundedPages() {
        WindowTrackingMemory memory = new WindowTrackingMemory();
        memory.addMessage(new UserMessage("model-1"));
        addUiMessages(memory, 100);
        memory.addMessage(new UserMessage("model-2"));
        addUiMessages(memory, 100);
        memory.addMessage(new UserMessage("model-3"));

        List<Message> messages = memory.getModelMessages(2);

        assertEquals(2, messages.size());
        assertEquals("model-2", messages.get(0).getTextContent());
        assertEquals("model-3", messages.get(1).getTextContent());
        assertTrue(memory.pageReads > 1);
        assertTrue(memory.largestPage <= 256);
    }

    @Test
    public void shouldAppendIdempotentlyAndUpdateWithExpectedVersion() {
        DefaultChatMemory memory = new DefaultChatMemory();
        UserMessage original = new UserMessage("pending");
        original.setMessageId("action-1");

        assertTrue(memory.addMessageIfAbsent(original));
        assertFalse(memory.addMessageIfAbsent(original.copy()));

        UserMessage resolved = original.copy();
        resolved.setContent("approved");
        assertTrue(memory.updateMessage(resolved, 0));
        assertEquals(1, resolved.getVersion());
        assertFalse(memory.updateMessage(original.copy(), 0));
        assertEquals("approved", memory.getMessages(1).get(0).getTextContent());
    }

    @Test
    public void shouldPreserveStorageStateWhenMessageIsCopied() {
        UserMessage source = new UserMessage("hello");
        source.setMessageId("message-1");
        source.setModelVisible(false);
        source.setVersion(3);
        source.putMetadata("tenant", "acme");

        UserMessage copy = source.copy();

        assertNotSame(source, copy);
        assertEquals("message-1", copy.getMessageId());
        assertFalse(copy.isModelVisible());
        assertEquals(3, copy.getVersion());
        assertEquals("acme", copy.getMetadata("tenant"));
    }

    private static void addUiMessages(DefaultChatMemory memory, int count) {
        for (int index = 0; index < count; index++) {
            UserMessage message = new UserMessage("ui-" + index);
            message.setModelVisible(false);
            memory.addMessage(message);
        }
    }

    private static final class WindowTrackingMemory extends DefaultChatMemory {
        private int pageReads;
        private int largestPage;

        @Override
        public synchronized List<Message> getMessages(int offset, int count) {
            pageReads++;
            largestPage = Math.max(largestPage, count);
            return super.getMessages(offset, count);
        }
    }
}

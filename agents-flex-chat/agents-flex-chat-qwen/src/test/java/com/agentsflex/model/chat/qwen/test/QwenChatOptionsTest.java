package com.agentsflex.model.chat.qwen.test;

import com.agentsflex.model.chat.qwen.QwenChatOptions;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class QwenChatOptionsTest {

    @Test
    public void shouldCopyBaseAndQwenSpecificOptions() {
        QwenChatOptions source = new QwenChatOptions();
        source.setModel("qwen-plus");
        source.setContextConversationId("conversation-1");
        source.setModalities(Arrays.asList("text"));
        source.setPresencePenalty(0.5f);
        source.setEnableSearch(true);
        source.setSearchOptions(new QwenChatOptions.SearchOptions()
            .setForcedSearch(true)
            .setSearchStrategy("pro"));

        QwenChatOptions copy = source.copy();

        assertNotSame(source, copy);
        assertEquals("qwen-plus", copy.getModel());
        assertEquals("conversation-1", copy.getContextConversationId());
        assertEquals(Arrays.asList("text"), copy.getModalities());
        assertNotSame(source.getModalities(), copy.getModalities());
        assertEquals(Float.valueOf(0.5f), copy.getPresencePenalty());
        assertEquals(Boolean.TRUE, copy.getEnableSearch());
        assertNotSame(source.getSearchOptions(), copy.getSearchOptions());
        assertEquals("pro", copy.getSearchOptions().getSearchStrategy());
    }
}

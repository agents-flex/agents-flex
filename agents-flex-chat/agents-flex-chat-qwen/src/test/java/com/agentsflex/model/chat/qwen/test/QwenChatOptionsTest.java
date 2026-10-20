package com.agentsflex.model.chat.qwen.test;

import com.agentsflex.model.chat.qwen.QwenChatOptions;
import com.agentsflex.model.chat.qwen.QwenChatConfig;
import com.agentsflex.model.chat.qwen.QwenRequestSpecBuilder;
import com.agentsflex.core.prompt.SimplePrompt;
import com.alibaba.fastjson2.JSON;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class QwenChatOptionsTest {

    @Test
    public void thinkingBudgetUsesEffectiveConfigAndRequestOverride() {
        QwenChatConfig config = new QwenChatConfig();
        config.setThinkingEnabled(true);
        QwenChatOptions options = new QwenChatOptions();
        options.setThinkingBudget(2048);
        QwenRequestSpecBuilder builder = new QwenRequestSpecBuilder();
        com.alibaba.fastjson2.JSONObject body = JSON.parseObject(builder.buildRequestBody(new SimplePrompt("hello"), options, config));
        assertTrue(body.getBooleanValue("enable_thinking"));
        assertEquals(2048, body.getIntValue("thinking_budget"));
        options.setThinkingEnabled(false);
        body = JSON.parseObject(builder.buildRequestBody(new SimplePrompt("hello"), options, config));
        assertFalse(body.getBooleanValue("enable_thinking"));
        assertFalse(body.containsKey("thinking_budget"));
        assertEquals(Boolean.TRUE, config.getThinkingEnabled());
    }

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

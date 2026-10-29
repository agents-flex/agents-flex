package com.agentsflex.core.model.chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证模型上下文容量配置、规范化和非法边界。
 */
public class ChatModelCapabilitiesTest {

    @Test
    public void baseConfigMustExposeStableModelCapabilities() {
        BaseChatConfig config = new BaseChatConfig();
        config.setProvider("provider");
        config.setModel("model");
        config.setContextWindowTokens(128000);
        config.setMaxOutputTokens(8192);

        ChatModelCapabilities value = config.toCapabilities();

        assertEquals("provider:model", value.getModelKey());
        assertEquals(128000, value.getContextWindowTokens());
        assertEquals(8192, value.getMaxOutputTokens());
        assertTrue(value.hasContextWindowTokens());
        assertFalse(ChatModelCapabilities.unknown().hasContextWindowTokens());
    }

    @Test(expected = IllegalArgumentException.class)
    public void contextWindowMustRejectNegativeValues() {
        new BaseChatConfig().setContextWindowTokens(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxOutputMustRejectNegativeValues() {
        new BaseChatConfig().setMaxOutputTokens(-1);
    }

    @Test
    public void capabilitiesMustNormalizeBlankAndTrimStableModelKey() {
        ChatModelCapabilities trimmed = new ChatModelCapabilities("  provider:model  ", 1, 2);
        ChatModelCapabilities blank = new ChatModelCapabilities("  ", 0, 0);

        assertEquals("provider:model", trimmed.getModelKey());
        assertEquals(null, blank.getModelKey());
        assertFalse(blank.hasContextWindowTokens());
        assertTrue(ChatModelCapabilities.unknown() == ChatModelCapabilities.unknown());
    }

    @Test
    public void capabilitiesMustRejectEveryNegativeLimit() {
        assertInvalid(-1, 0);
        assertInvalid(0, -1);
    }

    private static void assertInvalid(long context, long output) {
        try {
            new ChatModelCapabilities("model", context, output);
            org.junit.Assert.fail("Expected invalid capabilities");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be negative"));
        }
    }
}

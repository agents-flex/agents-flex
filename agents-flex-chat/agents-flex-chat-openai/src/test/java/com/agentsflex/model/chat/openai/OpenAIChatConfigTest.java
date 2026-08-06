package com.agentsflex.model.chat.openai;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** OpenAI 配置 Builder 对 BaseChatConfig 属性的覆盖测试。 */
public class OpenAIChatConfigTest {

    @Test
    public void shouldConfigureAllChatPropertiesThroughBuilder() {
        OpenAIChatConfig config = OpenAIChatConfig.builder()
            .apiKey("test-key")
            .supportImage(false)
            .supportImageBase64Only(true)
            .supportAudio(true)
            .supportVideo(false)
            .supportFile(true)
            .supportTool(false)
            .supportToolMessage(false)
            .supportThinking(false)
            .preserveThinkingEnable(true)
            .thinkingEnabled(true)
            .thinkingProtocol("deepseek")
            .observabilityEnabled(false)
            .logEnabled(false)
            .retryEnabled(false)
            .retryCount(5)
            .retryInitialDelayMs(250)
            .supportProviderTools(Arrays.asList("web_search"))
            .addSupportProviderTools("code_interpreter")
            .build();

        assertFalse(config.isSupportImage());
        assertTrue(config.isSupportImageBase64Only());
        assertTrue(config.isSupportAudio());
        assertFalse(config.isSupportVideo());
        assertTrue(config.isSupportFile());
        assertFalse(config.isSupportTool());
        assertFalse(config.isSupportToolMessage());
        assertFalse(config.isSupportThinking());
        assertTrue(config.isPreserveThinkingEnable());
        assertTrue(config.isThinkingEnabled());
        assertEquals("deepseek", config.getThinkingProtocol());
        assertFalse(config.isObservabilityEnabled());
        assertFalse(config.isLogEnabled());
        assertFalse(config.isRetryEnabled());
        assertEquals(5, config.getRetryCount());
        assertEquals(250, config.getRetryInitialDelayMs());
        assertTrue(config.isSupportProviderTools("web_search"));
        assertTrue(config.isSupportProviderTools("code_interpreter"));
    }
}

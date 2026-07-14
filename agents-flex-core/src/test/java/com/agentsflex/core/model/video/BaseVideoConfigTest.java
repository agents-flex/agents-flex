package com.agentsflex.core.model.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BaseVideoConfigTest {
    @Test
    public void shouldExposeCapabilitiesAndTaskDefaults() {
        BaseVideoConfig config = new BaseVideoConfig();
        config.setEndpoint("https://example.com/");
        config.setQueryPath("tasks/{taskId}");
        config.setApiKey("secret-key");
        config.setSupportTextToVideo(true);

        assertTrue(config.isSupportTextToVideo());
        assertFalse(config.isSupportImageToVideo());
        assertEquals("https://example.com/tasks/task-1", config.getQueryUrl("task-1"));
        assertEquals(10_000L, config.getPollIntervalMillis());
        assertEquals(10 * 60_000L, config.getTimeoutMillis());
        assertFalse(config.toString().contains("secret-key"));
    }
}

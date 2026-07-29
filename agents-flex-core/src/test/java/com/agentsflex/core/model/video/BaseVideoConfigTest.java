package com.agentsflex.core.model.video;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

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
        config.setModel("video-model");
        config.setSupportedDurations(Arrays.asList(5, 10));
        config.setSupportedResolutions(Arrays.asList("720p", "1080p"));
        config.setSupportedAspectRatios(Arrays.asList("16:9", "9:16"));
        config.setSupportedGenerationModes(Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO));

        assertTrue(config.isSupportTextToVideo());
        assertFalse(config.isSupportImageToVideo());
        assertEquals("https://example.com/tasks/task-1", config.getQueryUrl("task-1"));
        assertEquals(10_000L, config.getPollIntervalMillis());
        assertEquals(10 * 60_000L, config.getTimeoutMillis());
        assertEquals(Arrays.asList(5, 10), config.getSupportedDurations("video-model"));
        assertEquals(Arrays.asList("720p", "1080p"), config.getSupportedResolutions("video-model"));
        assertEquals(Arrays.asList("16:9", "9:16"), config.getSupportedAspectRatios("video-model"));
        assertEquals(Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO),
            config.getSupportedGenerationModes("video-model"));
        assertFalse(config.toString().contains("secret-key"));
    }
}

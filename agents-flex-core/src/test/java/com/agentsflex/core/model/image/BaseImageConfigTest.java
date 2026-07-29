package com.agentsflex.core.model.image;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BaseImageConfigTest {
    @Test
    public void shouldExposeCurrentModelGenerationOptions() {
        BaseImageConfig config = new BaseImageConfig();

        assertNull(config.getSupportedResolutions());
        assertNull(config.getSupportedAspectRatios());
        assertNull(config.getSupportedQualities());

        config.setSupportedResolutions(Arrays.asList("1K", "2K"));
        config.setSupportedAspectRatios(Arrays.asList("1:1", "16:9", "9:16"));
        config.setSupportedQualities(Arrays.asList("standard", "hd"));

        assertEquals(Arrays.asList("1K", "2K"), config.getSupportedResolutions());
        assertEquals(Arrays.asList("1:1", "16:9", "9:16"), config.getSupportedAspectRatios());
        assertEquals(Arrays.asList("standard", "hd"), config.getSupportedQualities());
    }
}

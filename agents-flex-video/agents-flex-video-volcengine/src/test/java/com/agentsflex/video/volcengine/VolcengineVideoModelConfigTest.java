package com.agentsflex.video.volcengine;

import com.agentsflex.core.model.video.VideoGenerationMode;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class VolcengineVideoModelConfigTest {
    @Test
    public void shouldExposeSeedanceOmniReferenceAndAudioCapabilities() {
        VolcengineVideoModelConfig config = new VolcengineVideoModelConfig();

        assertTrue(config.getSupportedGenerationModes().contains(VideoGenerationMode.FIRST_LAST_FRAME));
        assertTrue(config.getSupportedGenerationModes().contains(VideoGenerationMode.OMNI_REFERENCE));
        assertTrue(config.isSupportAudioGeneration());
        assertTrue(config.getSupportedResolutions().contains("4k"));
        assertTrue(config.getSupportedAspectRatios().contains("adaptive"));
        assertTrue(config.getSupportedDurations().contains(15));
    }
}

package com.agentsflex.video.gitee;

import com.agentsflex.core.model.video.VideoGenerationMode;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class GiteeVideoModelConfigTest {
    @Test
    public void shouldExposeGenerationModesAndAudioCapability() {
        GiteeVideoModelConfig config = new GiteeVideoModelConfig();

        assertEquals(Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO),
            config.getSupportedGenerationModes());
        assertFalse(config.isSupportAudioGeneration());
        assertEquals(Collections.singletonList(VideoGenerationMode.AUDIO_DRIVEN),
            config.getSupportedGenerationModes(GiteeVideoModels.DUIX_HEYGEM));
    }
}

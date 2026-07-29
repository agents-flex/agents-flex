package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.video.VideoGenerationMode;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AliyunVideoModelConfigTest {
    @Test
    public void shouldExposeHappyHorseCapabilitiesByModel() {
        AliyunHappyHorseVideoModelConfig config = new AliyunHappyHorseVideoModelConfig();

        assertEquals(Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO),
            config.getSupportedGenerationModes());
        assertTrue(config.getSupportedResolutions().contains("1080P"));
        assertTrue(config.getSupportedDurations().contains(15));
        assertTrue(config.isSupportAudioGeneration());

        config.setModel(AliyunVideoModels.HAPPYHORSE_1_1_I2V);

        assertEquals(Collections.singletonList(VideoGenerationMode.FIRST_FRAME),
            config.getSupportedGenerationModes());
        assertFalse(config.isSupportTextToVideo());
        assertTrue(config.isSupportImageToVideo());
        assertTrue(config.getSupportedAspectRatios().isEmpty());
    }

    @Test
    public void shouldExposeWanGenerationModesByRequestedModel() {
        AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();

        assertEquals(Collections.singletonList(VideoGenerationMode.FIRST_LAST_FRAME),
            config.getSupportedGenerationModes(AliyunVideoModels.WAN_2_1_KF2V_PLUS));
        assertEquals(Boolean.TRUE, config.getSupportAudioGeneration(AliyunVideoModels.WAN_2_6_R2V));
        assertEquals(java.util.Arrays.asList("720P", "1080P"),
            config.getSupportedResolutions(AliyunVideoModels.WAN_2_6_T2V));
        assertFalse(config.getSupportedDurations(AliyunVideoModels.WAN_2_6_R2V).contains(15));
    }
}

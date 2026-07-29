package com.agentsflex.video.volcengine;

import com.agentsflex.core.model.video.BaseVideoConfig;
import com.agentsflex.core.model.video.VideoGenerationMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VolcengineVideoModelConfig extends BaseVideoConfig {
    private static final List<String> STANDARD_RESOLUTIONS = Arrays.asList("480p", "720p", "1080p");
    private static final List<String> SEEDANCE_2_RESOLUTIONS = Arrays.asList("480p", "720p", "1080p", "4k");
    private static final List<String> ASPECT_RATIOS = Arrays.asList(
        "16:9", "4:3", "1:1", "3:4", "9:16", "21:9", "adaptive"
    );
    private static final List<Integer> SEEDANCE_2_DURATIONS = Arrays.asList(
        -1, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    );
    private static final List<Integer> SEEDANCE_1_5_DURATIONS = Arrays.asList(
        -1, 4, 5, 6, 7, 8, 9, 10, 11, 12
    );
    private static final List<Integer> SEEDANCE_1_0_DURATIONS = Arrays.asList(
        2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
    );

    public VolcengineVideoModelConfig() {
        setProvider("volcengine");
        setEndpoint("https://ark.cn-beijing.volces.com");
        setRequestPath("/api/v3/contents/generations/tasks");
        setQueryPath("/api/v3/contents/generations/tasks/{taskId}");
        setSupportTextToVideo(true);
        setSupportImageToVideo(true);
        setSupportFirstLastFrame(true);
        setSupportReferenceImages(true);
        setSupportVideoToVideo(true);
        setSupportAudioInput(true);
        setSupportAudioGeneration(true);
        setSupportCameraFixed(true);
        setSupportWatermark(true);
        setSupportSeed(true);
        setModel(VolcengineVideoModels.SEEDANCE_2_0);
    }

    @Override
    public void setModel(String model) {
        super.setModel(model);
        setSupportedResolutions(resolutionsForModel(model));
        setSupportedAspectRatios(aspectRatiosForModel(model));
        setSupportedDurations(durationsForModel(model));
        setSupportedGenerationModes(modesForModel(model));
        setSupportAudioGeneration(audioGenerationForModel(model));
    }

    @Override
    public List<VideoGenerationMode> getSupportedGenerationModes(String model) {
        return modesForModel(model);
    }

    @Override
    public List<String> getSupportedResolutions(String model) {
        return resolutionsForModel(model);
    }

    @Override
    public List<String> getSupportedAspectRatios(String model) {
        return aspectRatiosForModel(model);
    }

    @Override
    public List<Integer> getSupportedDurations(String model) {
        return durationsForModel(model);
    }

    @Override
    public Boolean getSupportAudioGeneration(String model) {
        return audioGenerationForModel(model);
    }

    private static List<VideoGenerationMode> modesForModel(String model) {
        if (VolcengineVideoModels.SEEDANCE_2_0.equals(model)) {
            return Arrays.asList(
                VideoGenerationMode.TEXT_TO_VIDEO,
                VideoGenerationMode.FIRST_FRAME,
                VideoGenerationMode.FIRST_LAST_FRAME,
                VideoGenerationMode.REFERENCE_IMAGES,
                VideoGenerationMode.OMNI_REFERENCE,
                VideoGenerationMode.VIDEO_TO_VIDEO,
                VideoGenerationMode.AUDIO_DRIVEN
            );
        }
        if (VolcengineVideoModels.SEEDANCE_1_5_PRO.equals(model)) {
            return Arrays.asList(
                VideoGenerationMode.TEXT_TO_VIDEO,
                VideoGenerationMode.FIRST_FRAME,
                VideoGenerationMode.FIRST_LAST_FRAME
            );
        }
        if (VolcengineVideoModels.SEEDANCE_1_0_PRO.equals(model)) {
            return Arrays.asList(VideoGenerationMode.TEXT_TO_VIDEO, VideoGenerationMode.FIRST_FRAME);
        }
        if (VolcengineVideoModels.SEEDANCE_1_0_LITE.equals(model)) {
            return Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO);
        }
        return null;
    }

    private static Boolean audioGenerationForModel(String model) {
        if (VolcengineVideoModels.SEEDANCE_2_0.equals(model) ||
            VolcengineVideoModels.SEEDANCE_1_5_PRO.equals(model)) return true;
        if (VolcengineVideoModels.SEEDANCE_1_0_PRO.equals(model) ||
            VolcengineVideoModels.SEEDANCE_1_0_LITE.equals(model)) return false;
        return null;
    }

    private static List<String> resolutionsForModel(String model) {
        if (VolcengineVideoModels.SEEDANCE_2_0.equals(model)) return SEEDANCE_2_RESOLUTIONS;
        return isSeedance1Model(model) ? STANDARD_RESOLUTIONS : null;
    }

    private static List<String> aspectRatiosForModel(String model) {
        return isKnownModel(model) ? ASPECT_RATIOS : null;
    }

    private static List<Integer> durationsForModel(String model) {
        if (VolcengineVideoModels.SEEDANCE_2_0.equals(model)) return SEEDANCE_2_DURATIONS;
        if (VolcengineVideoModels.SEEDANCE_1_5_PRO.equals(model)) return SEEDANCE_1_5_DURATIONS;
        return isSeedance10Model(model) ? SEEDANCE_1_0_DURATIONS : null;
    }

    private static boolean isKnownModel(String model) {
        return VolcengineVideoModels.SEEDANCE_2_0.equals(model) || isSeedance1Model(model);
    }

    private static boolean isSeedance1Model(String model) {
        return VolcengineVideoModels.SEEDANCE_1_5_PRO.equals(model) || isSeedance10Model(model);
    }

    private static boolean isSeedance10Model(String model) {
        return VolcengineVideoModels.SEEDANCE_1_0_PRO.equals(model) ||
            VolcengineVideoModels.SEEDANCE_1_0_LITE.equals(model);
    }
}

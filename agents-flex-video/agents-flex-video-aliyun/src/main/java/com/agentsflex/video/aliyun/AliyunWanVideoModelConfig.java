package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.video.BaseVideoConfig;
import com.agentsflex.core.model.video.VideoGenerationMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 阿里云通义万相视频模型配置。
 * <p>
 * 能力字段描述 Wan 模型族的公共能力；选择具体模型后，仍应以该模型的官方参数限制为准。
 */
public class AliyunWanVideoModelConfig extends BaseVideoConfig {
    private static final List<String> WAN_2_6_RESOLUTIONS = Arrays.asList("720P", "1080P");
    private static final List<Integer> WAN_2_6_LONG_DURATIONS = Arrays.asList(
        2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    );
    private static final List<Integer> WAN_2_6_REFERENCE_DURATIONS = Arrays.asList(
        2, 3, 4, 5, 6, 7, 8, 9, 10
    );

    public AliyunWanVideoModelConfig() {
        setProvider("aliyun");
        setEndpoint("https://dashscope.aliyuncs.com");
        setRequestPath("/api/v1/services/aigc/video-generation/video-synthesis");
        setQueryPath("/api/v1/tasks/{taskId}");
        setSupportTextToVideo(true);
        setSupportImageToVideo(true);
        setSupportFirstLastFrame(true);
        setSupportReferenceImages(true);
        setSupportAudioInput(true);
        setSupportAudioGeneration(true);
        setSupportNegativePrompt(true);
        setSupportPromptExtend(true);
        setSupportWatermark(true);
        setSupportSeed(true);
        setModel(AliyunVideoModels.WAN_2_6_T2V);
    }

    @Override
    public void setModel(String model) {
        super.setModel(model);
        setSupportedResolutions(resolutionsForModel(model));
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
    public List<Integer> getSupportedDurations(String model) {
        return durationsForModel(model);
    }

    @Override
    public Boolean getSupportAudioGeneration(String model) {
        return audioGenerationForModel(model);
    }

    private static List<VideoGenerationMode> modesForModel(String model) {
        if (model == null) return null;
        String value = model.toLowerCase();
        if (value.endsWith("-t2v") || value.endsWith("-t2v-plus")) {
            return Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO);
        }
        if (value.endsWith("-i2v") || value.endsWith("-i2v-plus")) {
            return Collections.singletonList(VideoGenerationMode.FIRST_FRAME);
        }
        if (value.endsWith("-r2v")) {
            return Collections.singletonList(VideoGenerationMode.REFERENCE_IMAGES);
        }
        if (value.contains("kf2v")) {
            return Collections.singletonList(VideoGenerationMode.FIRST_LAST_FRAME);
        }
        return null;
    }

    private static List<String> resolutionsForModel(String model) {
        return isWan26Model(model) ? WAN_2_6_RESOLUTIONS : null;
    }

    private static List<Integer> durationsForModel(String model) {
        if (!isWan26Model(model)) return null;
        return model.toLowerCase().endsWith("-r2v")
            ? WAN_2_6_REFERENCE_DURATIONS : WAN_2_6_LONG_DURATIONS;
    }

    private static Boolean audioGenerationForModel(String model) {
        return isWan26Model(model) ? true : null;
    }

    private static boolean isWan26Model(String model) {
        return model != null && model.toLowerCase().startsWith("wan2.6-");
    }
}

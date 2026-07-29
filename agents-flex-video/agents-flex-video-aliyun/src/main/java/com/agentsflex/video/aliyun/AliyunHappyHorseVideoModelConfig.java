package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.video.BaseVideoConfig;
import com.agentsflex.core.model.video.VideoGenerationMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 阿里云 HappyHorse 视频模型配置。
 * <p>
 * HappyHorse 模型族支持文生视频、首帧图生视频、参考生视频和视频编辑，
 * 不同模式由配置或请求中的具体模型名称决定。
 */
public class AliyunHappyHorseVideoModelConfig extends BaseVideoConfig {
    private static final List<String> RESOLUTIONS = Arrays.asList("720P", "1080P");
    private static final List<String> ASPECT_RATIOS = Arrays.asList(
        "16:9", "9:16", "1:1", "4:3", "3:4", "4:5", "5:4", "9:21", "21:9"
    );
    private static final List<Integer> DURATIONS = Arrays.asList(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    );

    public AliyunHappyHorseVideoModelConfig() {
        setProvider("aliyun");
        setEndpoint("https://dashscope.aliyuncs.com");
        setRequestPath("/api/v1/services/aigc/video-generation/video-synthesis");
        setQueryPath("/api/v1/tasks/{taskId}");
        setSupportTextToVideo(true);
        setSupportImageToVideo(true);
        setSupportReferenceImages(true);
        setSupportVideoToVideo(true);
        setSupportWatermark(true);
        setSupportSeed(true);
        setMaxReferenceImages(9);
        setModel(AliyunVideoModels.HAPPYHORSE_1_1_T2V);
    }

    @Override
    public void setModel(String model) {
        super.setModel(model);
        setSupportedResolutions(resolutionsForModel(model));
        setSupportedAspectRatios(aspectRatiosForModel(model));
        setSupportedDurations(durationsForModel(model));
        setSupportedGenerationModes(modesForModel(model));
        setSupportAudioGeneration(isHappyHorseModel(model) ? true : null);
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
    public List<VideoGenerationMode> getSupportedGenerationModes(String model) {
        return modesForModel(model);
    }

    @Override
    public Boolean getSupportAudioGeneration(String model) {
        return isHappyHorseModel(model) ? true : null;
    }

    private static List<String> resolutionsForModel(String model) {
        return isHappyHorseModel(model) ? RESOLUTIONS : null;
    }

    private static List<String> aspectRatiosForModel(String model) {
        if (isI2v(model) || isVideoEdit(model)) return Collections.emptyList();
        return isHappyHorseModel(model) ? ASPECT_RATIOS : null;
    }

    private static List<Integer> durationsForModel(String model) {
        if (isVideoEdit(model)) return Collections.emptyList();
        return isHappyHorseModel(model) ? DURATIONS : null;
    }

    private static List<VideoGenerationMode> modesForModel(String model) {
        if (isT2v(model)) return Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO);
        if (isI2v(model)) return Collections.singletonList(VideoGenerationMode.FIRST_FRAME);
        if (isR2v(model)) return Collections.singletonList(VideoGenerationMode.REFERENCE_IMAGES);
        if (isVideoEdit(model)) return Collections.singletonList(VideoGenerationMode.VIDEO_TO_VIDEO);
        return null;
    }

    private static boolean isHappyHorseModel(String model) {
        return model != null && model.toLowerCase().startsWith("happyhorse-");
    }

    private static boolean isT2v(String model) {
        return model != null && model.toLowerCase().endsWith("-t2v");
    }

    private static boolean isI2v(String model) {
        return model != null && model.toLowerCase().endsWith("-i2v");
    }

    private static boolean isR2v(String model) {
        return model != null && model.toLowerCase().endsWith("-r2v");
    }

    private static boolean isVideoEdit(String model) {
        return model != null && model.toLowerCase().endsWith("-video-edit");
    }
}

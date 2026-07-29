package com.agentsflex.video.gitee;

import com.agentsflex.core.model.video.BaseVideoConfig;
import com.agentsflex.core.model.video.VideoGenerationMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Gitee AI 模力方舟视频模型配置。
 * <p>
 * 除文生视频默认路径外，还保存图生视频、图片视频生成和音频视频生成的提交路径。
 * 模型会根据 {@code GenerateVideoRequest} 中的素材自动选择对应路径。
 */
public class GiteeVideoModelConfig extends BaseVideoConfig {
    /**
     * 图生视频异步任务提交路径。
     */
    private String imageToVideoPath = "/async/videos/image-to-video";
    /**
     * 参考图片与驱动视频生成视频的异步任务提交路径。
     */
    private String imageVideoToVideoPath = "/async/videos/image-video-to-video";
    /**
     * 参考音频与驱动视频生成视频的异步任务提交路径。
     */
    private String audioVideoToVideoPath = "/async/videos/audio-video-to-video";

    public GiteeVideoModelConfig() {
        setProvider("gitee");
        setEndpoint("https://ai.gitee.com/v1");
        setRequestPath("/async/videos/generations");
        setQueryPath("/task/{taskId}");
        setSupportTextToVideo(true);
        setSupportImageToVideo(true);
        setSupportReferenceImages(true);
        setSupportVideoToVideo(true);
        setSupportAudioInput(true);
        setSupportAudioGeneration(false);
        setModel(GiteeVideoModels.HAPPYHORSE_1_1);
    }

    public String getImageToVideoPath() {
        return imageToVideoPath;
    }

    public void setImageToVideoPath(String imageToVideoPath) {
        this.imageToVideoPath = normalizePath(imageToVideoPath);
    }

    public String getImageVideoToVideoPath() {
        return imageVideoToVideoPath;
    }

    public void setImageVideoToVideoPath(String imageVideoToVideoPath) {
        this.imageVideoToVideoPath = normalizePath(imageVideoToVideoPath);
    }

    public String getAudioVideoToVideoPath() {
        return audioVideoToVideoPath;
    }

    public void setAudioVideoToVideoPath(String audioVideoToVideoPath) {
        this.audioVideoToVideoPath = normalizePath(audioVideoToVideoPath);
    }

    /**
     * @return 完整图生视频提交地址
     */
    public String getImageToVideoUrl() {
        return getEndpoint() + imageToVideoPath;
    }

    /**
     * @return 完整图片视频生成提交地址
     */
    public String getImageVideoToVideoUrl() {
        return getEndpoint() + imageVideoToVideoPath;
    }

    /**
     * @return 完整音频视频生成提交地址
     */
    public String getAudioVideoToVideoUrl() {
        return getEndpoint() + audioVideoToVideoPath;
    }

    private static String normalizePath(String path) {
        return path != null && !path.startsWith("/") ? "/" + path : path;
    }

    @Override
    public void setModel(String model) {
        super.setModel(model);
        setSupportedGenerationModes(modesForModel(model));
        setSupportAudioGeneration(isKnownModel(model) ? false : null);
    }

    @Override
    public List<VideoGenerationMode> getSupportedGenerationModes(String model) {
        return modesForModel(model);
    }

    @Override
    public Boolean getSupportAudioGeneration(String model) {
        return isKnownModel(model) ? false : null;
    }

    private static List<VideoGenerationMode> modesForModel(String model) {
        if (GiteeVideoModels.DUIX_HEYGEM.equals(model)) {
            return Collections.singletonList(VideoGenerationMode.AUDIO_DRIVEN);
        }
        if (GiteeVideoModels.LTX_2.equals(model) || GiteeVideoModels.WAN_2_2_I2V_A14B.equals(model) ||
            GiteeVideoModels.INFINITE_TALK.equals(model)) {
            return Collections.singletonList(VideoGenerationMode.FIRST_FRAME);
        }
        if (GiteeVideoModels.HAPPYHORSE_1_0.equals(model) || GiteeVideoModels.WAN_2_7.equals(model)) {
            return Arrays.asList(VideoGenerationMode.TEXT_TO_VIDEO, VideoGenerationMode.OMNI_REFERENCE);
        }
        return isKnownModel(model) ? Collections.singletonList(VideoGenerationMode.TEXT_TO_VIDEO) : null;
    }

    private static boolean isKnownModel(String model) {
        return GiteeVideoModels.HAPPYHORSE_1_1.equals(model) ||
            GiteeVideoModels.HAPPYHORSE_1_0.equals(model) ||
            GiteeVideoModels.WAN_2_7.equals(model) ||
            GiteeVideoModels.WAN_2_1_T2V_14B.equals(model) ||
            GiteeVideoModels.VIDU_Q3_TURBO.equals(model) ||
            GiteeVideoModels.VIDU_Q3_PRO.equals(model) ||
            GiteeVideoModels.VIDU_Q2_TURBO.equals(model) ||
            GiteeVideoModels.VIDU_Q2_PRO.equals(model) ||
            GiteeVideoModels.HUNYUAN_VIDEO_1_5.equals(model) ||
            GiteeVideoModels.LTX_2.equals(model) ||
            GiteeVideoModels.WAN_2_2_I2V_A14B.equals(model) ||
            GiteeVideoModels.INFINITE_TALK.equals(model) ||
            GiteeVideoModels.DUIX_HEYGEM.equals(model);
    }
}

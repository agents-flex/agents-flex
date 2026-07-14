package com.agentsflex.video.gitee;

import com.agentsflex.core.model.video.BaseVideoConfig;

/**
 * Gitee AI 模力方舟视频模型配置。
 * <p>
 * 除文生视频默认路径外，还保存图生视频、图片视频生成和音频视频生成的提交路径。
 * 模型会根据 {@code GenerateVideoRequest} 中的素材自动选择对应路径。
 */
public class GiteeVideoModelConfig extends BaseVideoConfig {
    /** 图生视频异步任务提交路径。 */
    private String imageToVideoPath = "/async/videos/image-to-video";
    /** 参考图片与驱动视频生成视频的异步任务提交路径。 */
    private String imageVideoToVideoPath = "/async/videos/image-video-to-video";
    /** 参考音频与驱动视频生成视频的异步任务提交路径。 */
    private String audioVideoToVideoPath = "/async/videos/audio-video-to-video";

    public GiteeVideoModelConfig() {
        setProvider("gitee");
        setEndpoint("https://ai.gitee.com/v1");
        setRequestPath("/async/videos/generations");
        setQueryPath("/task/{taskId}");
        setModel(GiteeVideoModels.HAPPYHORSE_1_1);

        setSupportTextToVideo(true);
        setSupportImageToVideo(true);
        setSupportReferenceImages(true);
        setSupportVideoToVideo(true);
        setSupportAudioInput(true);
    }

    public String getImageToVideoPath() { return imageToVideoPath; }
    public void setImageToVideoPath(String imageToVideoPath) {
        this.imageToVideoPath = normalizePath(imageToVideoPath);
    }
    public String getImageVideoToVideoPath() { return imageVideoToVideoPath; }
    public void setImageVideoToVideoPath(String imageVideoToVideoPath) {
        this.imageVideoToVideoPath = normalizePath(imageVideoToVideoPath);
    }
    public String getAudioVideoToVideoPath() { return audioVideoToVideoPath; }
    public void setAudioVideoToVideoPath(String audioVideoToVideoPath) {
        this.audioVideoToVideoPath = normalizePath(audioVideoToVideoPath);
    }

    /** @return 完整图生视频提交地址 */
    public String getImageToVideoUrl() { return getEndpoint() + imageToVideoPath; }
    /** @return 完整图片视频生成提交地址 */
    public String getImageVideoToVideoUrl() { return getEndpoint() + imageVideoToVideoPath; }
    /** @return 完整音频视频生成提交地址 */
    public String getAudioVideoToVideoUrl() { return getEndpoint() + audioVideoToVideoPath; }

    private static String normalizePath(String path) {
        return path != null && !path.startsWith("/") ? "/" + path : path;
    }
}

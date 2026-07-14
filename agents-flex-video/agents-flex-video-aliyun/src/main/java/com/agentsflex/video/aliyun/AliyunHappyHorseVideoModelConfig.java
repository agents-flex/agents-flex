package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.video.BaseVideoConfig;

/**
 * 阿里云 HappyHorse 视频模型配置。
 * <p>
 * HappyHorse 模型族支持文生视频、首帧图生视频、参考生视频和视频编辑，
 * 不同模式由配置或请求中的具体模型名称决定。
 */
public class AliyunHappyHorseVideoModelConfig extends BaseVideoConfig {

    public AliyunHappyHorseVideoModelConfig() {
        setProvider("aliyun");
        setEndpoint("https://dashscope.aliyuncs.com");
        setRequestPath("/api/v1/services/aigc/video-generation/video-synthesis");
        setQueryPath("/api/v1/tasks/{taskId}");
        setModel(AliyunVideoModels.HAPPYHORSE_1_1_T2V);

        setSupportTextToVideo(true);
        setSupportImageToVideo(true);
        setSupportReferenceImages(true);
        setSupportVideoToVideo(true);
        setSupportWatermark(true);
        setSupportSeed(true);
        setMaxReferenceImages(9);
    }
}

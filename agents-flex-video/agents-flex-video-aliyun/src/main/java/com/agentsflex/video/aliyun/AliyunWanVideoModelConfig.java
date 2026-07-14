package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.video.BaseVideoConfig;

/**
 * 阿里云通义万相视频模型配置。
 * <p>
 * 能力字段描述 Wan 模型族的公共能力；选择具体模型后，仍应以该模型的官方参数限制为准。
 */
public class AliyunWanVideoModelConfig extends BaseVideoConfig {

    public AliyunWanVideoModelConfig() {
        setProvider("aliyun");
        setEndpoint("https://dashscope.aliyuncs.com");
        setRequestPath("/api/v1/services/aigc/video-generation/video-synthesis");
        setQueryPath("/api/v1/tasks/{taskId}");
        setModel(AliyunVideoModels.WAN_2_6_T2V);

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
    }
}

package com.agentsflex.video.volcengine;

import com.agentsflex.core.model.video.BaseVideoConfig;

public class VolcengineVideoModelConfig extends BaseVideoConfig {

    public VolcengineVideoModelConfig() {
        setProvider("volcengine");
        setEndpoint("https://ark.cn-beijing.volces.com");
        setRequestPath("/api/v3/contents/generations/tasks");
        setQueryPath("/api/v3/contents/generations/tasks/{taskId}");
        setModel(VolcengineVideoModels.SEEDANCE_2_0);

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
    }
}

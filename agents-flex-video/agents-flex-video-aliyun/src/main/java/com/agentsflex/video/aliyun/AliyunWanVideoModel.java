package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云通义万相视频模型。
 * <p>
 * 负责将统一的 {@link GenerateVideoRequest} 映射为 Wan 模型族使用的
 * {@code img_url}、{@code first_frame_url}、{@code reference_urls} 等协议字段。
 */
public class AliyunWanVideoModel extends AbstractAliyunVideoModel<AliyunWanVideoModelConfig> {

    /**
     * 使用指定配置创建通义万相视频模型。
     *
     * @param config API Key、默认模型、服务地址和轮询参数
     */
    public AliyunWanVideoModel(AliyunWanVideoModelConfig config) {
        super(config);
    }

    AliyunWanVideoModel(AliyunWanVideoModelConfig config, HttpClient httpClient) {
        super(config, httpClient);
    }

    @Override
    protected String validate(String model, GenerateVideoRequest request) {
        String value = model.toLowerCase();
        if (!value.startsWith("wan") && !value.startsWith("wanx")) {
            return "AliyunWanVideoModel only supports Wan video models";
        }
        return null;
    }

    @Override
    protected JSONObject buildInput(String model, GenerateVideoRequest request) {
        JSONObject input = new JSONObject();
        putIfNotEmpty(input, "prompt", request.getPrompt());
        putIfNotEmpty(input, "negative_prompt", request.getNegativePrompt());
        if (request.getLastFrame() != null) {
            putImage(input, "first_frame_url", request.getFirstFrame());
            putImage(input, "last_frame_url", request.getLastFrame());
        } else {
            putImage(input, "img_url", request.getFirstFrame());
        }
        if (request.getReferenceImages() != null && !request.getReferenceImages().isEmpty()) {
            List<String> urls = new ArrayList<>();
            for (Image image : request.getReferenceImages()) {
                if (image != null && StringUtil.hasText(image.getUrlOrBase64())) {
                    urls.add(image.getUrlOrBase64());
                }
            }
            if (!urls.isEmpty()) input.put("reference_urls", urls);
        }
        if (request.getSourceVideo() != null) {
            putIfNotEmpty(input, "video_url", request.getSourceVideo().getUrl());
        }
        putIfNotEmpty(input, "audio_url", request.getAudioUrl());
        return input;
    }

    @Override
    protected JSONObject buildParameters(String model, GenerateVideoRequest request) {
        JSONObject parameters = new JSONObject();
        putIfNotEmpty(parameters, "size", request.getSizeString());
        putIfNotEmpty(parameters, "resolution", request.getResolution());
        putIfNotEmpty(parameters, "ratio", request.getAspectRatio());
        putIfNotNull(parameters, "duration", request.getDuration());
        putIfNotNull(parameters, "fps", request.getFps());
        putIfNotNull(parameters, "seed", request.getSeed());
        putIfNotNull(parameters, "watermark", request.getWatermark());
        putIfNotNull(parameters, "prompt_extend", request.getPromptExtend());
        putIfNotNull(parameters, "generate_audio", request.getGenerateAudio());
        return parameters;
    }

    private void putImage(JSONObject input, String key, Image image) {
        if (image != null) putIfNotEmpty(input, key, image.getUrlOrBase64());
    }
}

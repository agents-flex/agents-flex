package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * 阿里云 HappyHorse 视频模型。
 * <p>
 * 该模型直接实现 HappyHorse 的 {@code input.media[]} 协议，并根据具体模型名称
 * 校验文生视频、首帧图生视频、参考生视频和视频编辑所需的素材。
 */
public class AliyunHappyHorseVideoModel
    extends AbstractAliyunVideoModel<AliyunHappyHorseVideoModelConfig> {

    /**
     * 使用指定配置创建 HappyHorse 视频模型。
     *
     * @param config API Key、默认模型、服务地址和轮询参数
     */
    public AliyunHappyHorseVideoModel(AliyunHappyHorseVideoModelConfig config) {
        super(config);
    }

    AliyunHappyHorseVideoModel(AliyunHappyHorseVideoModelConfig config, HttpClient httpClient) {
        super(config, httpClient);
    }

    @Override
    protected String validate(String model, GenerateVideoRequest request) {
        if (!model.toLowerCase().startsWith("happyhorse-")) {
            return "AliyunHappyHorseVideoModel only supports HappyHorse video models";
        }
        if (!isI2v(model) && StringUtil.noText(request.getPrompt())) {
            return "HappyHorse requires a prompt";
        }
        if (request.getLastFrame() != null) {
            return "HappyHorse does not support lastFrame";
        }
        if (StringUtil.hasText(request.getAudioUrl())) {
            return "HappyHorse does not support audioUrl";
        }

        if (isT2v(model)) {
            if (request.getFirstFrame() != null || request.getSourceVideo() != null ||
                imageCount(request.getReferenceImages()) > 0) {
                return "HappyHorse text-to-video does not accept media inputs";
            }
        } else if (isI2v(model)) {
            if (request.getFirstFrame() == null ||
                StringUtil.noText(request.getFirstFrame().getUrlOrBase64())) {
                return "HappyHorse image-to-video requires one firstFrame";
            }
            if (request.getSourceVideo() != null || imageCount(request.getReferenceImages()) > 0) {
                return "HappyHorse image-to-video only accepts one firstFrame";
            }
        } else if (isR2v(model)) {
            int count = imageCount(request.getReferenceImages());
            if (count < 1 || count > 9) {
                return "HappyHorse reference-to-video requires 1 to 9 referenceImages";
            }
            if (request.getFirstFrame() != null || request.getSourceVideo() != null) {
                return "HappyHorse reference-to-video only accepts referenceImages";
            }
        } else if (isVideoEdit(model)) {
            if (request.getSourceVideo() == null || StringUtil.noText(request.getSourceVideo().getUrl())) {
                return "HappyHorse video editing requires one sourceVideo URL";
            }
            if (request.getFirstFrame() != null) {
                return "HappyHorse video editing does not accept firstFrame";
            }
            if (imageCount(request.getReferenceImages()) > 5) {
                return "HappyHorse video editing supports at most 5 referenceImages";
            }
        } else {
            return "Unsupported HappyHorse video model: " + model;
        }
        return null;
    }

    @Override
    protected JSONObject buildInput(String model, GenerateVideoRequest request) {
        JSONObject input = new JSONObject();
        putIfNotEmpty(input, "prompt", request.getPrompt());

        JSONArray media = new JSONArray();
        if (request.getSourceVideo() != null && StringUtil.hasText(request.getSourceVideo().getUrl())) {
            media.add(media("video", request.getSourceVideo().getUrl()));
        }
        if (request.getFirstFrame() != null &&
            StringUtil.hasText(request.getFirstFrame().getUrlOrBase64())) {
            media.add(media("first_frame", request.getFirstFrame().getUrlOrBase64()));
        }
        if (request.getReferenceImages() != null) {
            for (Image image : request.getReferenceImages()) {
                if (image != null && StringUtil.hasText(image.getUrlOrBase64())) {
                    media.add(media("reference_image", image.getUrlOrBase64()));
                }
            }
        }
        if (!media.isEmpty()) input.put("media", media);
        return input;
    }

    @Override
    protected JSONObject buildParameters(String model, GenerateVideoRequest request) {
        JSONObject parameters = new JSONObject();
        putIfNotEmpty(parameters, "resolution", request.getResolution());
        if (!isI2v(model) && !isVideoEdit(model)) {
            putIfNotEmpty(parameters, "ratio", request.getAspectRatio());
        }
        if (!isVideoEdit(model)) {
            putIfNotNull(parameters, "duration", request.getDuration());
        }
        putIfNotNull(parameters, "watermark", request.getWatermark());
        putIfNotNull(parameters, "seed", request.getSeed());
        return parameters;
    }

    private static JSONObject media(String type, String url) {
        JSONObject media = new JSONObject();
        media.put("type", type);
        media.put("url", url);
        return media;
    }

    private static int imageCount(List<Image> images) {
        if (images == null) return 0;
        int count = 0;
        for (Image image : images) {
            if (image != null && StringUtil.hasText(image.getUrlOrBase64())) count++;
        }
        return count;
    }

    private static boolean isI2v(String model) {
        return model.toLowerCase().endsWith("-i2v");
    }

    private static boolean isT2v(String model) {
        return model.toLowerCase().endsWith("-t2v");
    }

    private static boolean isR2v(String model) {
        return model.toLowerCase().endsWith("-r2v");
    }

    private static boolean isVideoEdit(String model) {
        return model.toLowerCase().endsWith("-video-edit");
    }
}

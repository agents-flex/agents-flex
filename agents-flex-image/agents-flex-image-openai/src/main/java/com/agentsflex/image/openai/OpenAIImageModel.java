/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.openai;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.BaseImageModel;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI {@code /v1/images/generations} 同步图片生成适配器。
 */
public class OpenAIImageModel extends BaseImageModel<OpenAIImageModelConfig> {
    /**
     * GPT Image 背景模式扩展参数：{@code transparent}、{@code opaque} 或 {@code auto}。
     */
    public static final String OPTION_BACKGROUND = "background";
    /**
     * GPT Image 内容审核级别扩展参数：{@code low} 或 {@code auto}。
     */
    public static final String OPTION_MODERATION = "moderation";
    /**
     * JPEG/WebP 输出压缩级别扩展参数，范围为 0 到 100。
     */
    public static final String OPTION_OUTPUT_COMPRESSION = "output_compression";

    private final AgentsFlexHttpClient agentsFlexHttpClient;

    /**
     * 使用默认 HTTP 客户端创建 OpenAI 图片模型。
     */
    public OpenAIImageModel(OpenAIImageModelConfig config) {
        this(config, AgentsFlexHttpClient.getDefault());
    }

    /**
     * 供同包测试注入 HTTP 客户端。
     */
    OpenAIImageModel(OpenAIImageModelConfig config, AgentsFlexHttpClient agentsFlexHttpClient) {
        super(config);
        if (agentsFlexHttpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.agentsFlexHttpClient = agentsFlexHttpClient;
    }

    /**
     * 构建同步 Images API 请求并解析 URL 或 Base64 图片响应。
     */
    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        ImageResponse validationError = validate(request);
        if (validationError != null) return validationError;

        JSONObject payload = new JSONObject();
        if (request.getOptions() != null) payload.putAll(request.getOptions());
        payload.remove("stream");
        payload.remove("partial_images");

        String model = resolveModel(request);
        payload.put("model", model);
        payload.put("prompt", request.getPrompt());
        putIfNotNull(payload, "n", request.getN());
        putIfNotEmpty(payload, "size", resolveSize(request));
        putIfNotEmpty(payload, "quality", request.getQuality());
        putIfNotEmpty(payload, "style", request.getStyle());
        putIfNotEmpty(payload, "response_format", request.getResponseFormat());
        putIfNotEmpty(payload, "output_format", request.getOutputFormat());
        putIfNotEmpty(payload, "user", request.getUser());

        String json = agentsFlexHttpClient.post(config.getFullUrl(), headers(), payload.toJSONString());
        return parseResponse(json, request.getOutputFormat());
    }

    private ImageResponse validate(GenerateImageRequest request) {
        if (request == null) return ImageResponse.error("request must not be null");
        String model = resolveModel(request);
        if (StringUtil.noText(model)) return ImageResponse.error("image model must not be empty");
        if (StringUtil.noText(request.getPrompt())) return ImageResponse.error("prompt must not be empty");
        List<Image> inputImages = request.getInputImages();
        if (inputImages != null && !inputImages.isEmpty()) {
            return ImageResponse.error("OpenAI image generation does not accept input images");
        }
        if (Boolean.TRUE.equals(request.getOption("stream")) || request.getOption("partial_images") != null) {
            return ImageResponse.error("streaming image generation is not supported by the synchronous ImageModel API");
        }

        int promptLimit = OpenAIImageModels.DALL_E_2.equals(model) ? 1000 :
            OpenAIImageModels.DALL_E_3.equals(model) ? 4000 : 32000;
        if (request.getPrompt().length() > promptLimit) {
            return ImageResponse.error("prompt must not exceed " + promptLimit + " characters for " + model);
        }
        Integer n = request.getN();
        if (n != null && (n < 1 || n > 10)) return ImageResponse.error("n must be between 1 and 10");
        if (OpenAIImageModels.DALL_E_3.equals(model) && n != null && n != 1) {
            return ImageResponse.error("dall-e-3 only supports n=1");
        }
        if (OpenAIImageModelConfig.isGptImageModel(model) && StringUtil.hasText(request.getResponseFormat())) {
            return ImageResponse.error("responseFormat is not supported by GPT Image models");
        }
        if (!OpenAIImageModelConfig.isGptImageModel(model) && StringUtil.hasText(request.getOutputFormat())) {
            return ImageResponse.error("outputFormat is only supported by GPT Image models");
        }
        if (StringUtil.hasText(request.getStyle()) && !OpenAIImageModels.DALL_E_3.equals(model)) {
            return ImageResponse.error("style is only supported by dall-e-3");
        }
        ImageResponse valueError = validateSupportedValue("size", resolveSize(request),
            OpenAIImageModelConfig.supportedResolutions(model), model);
        if (valueError != null) return valueError;
        valueError = validateSupportedValue("quality", request.getQuality(),
            OpenAIImageModelConfig.supportedQualities(model), model);
        if (valueError != null) return valueError;
        return validateOptions(request);
    }

    private ImageResponse validateOptions(GenerateImageRequest request) {
        Object compression = request.getOption(OPTION_OUTPUT_COMPRESSION);
        if (compression instanceof Number) {
            int value = ((Number) compression).intValue();
            if (value < 0 || value > 100) {
                return ImageResponse.error("output_compression must be between 0 and 100");
            }
        }
        Object background = request.getOption(OPTION_BACKGROUND);
        if ("transparent".equals(background) && "jpeg".equals(request.getOutputFormat())) {
            return ImageResponse.error("transparent background requires png or webp outputFormat");
        }
        return null;
    }

    private ImageResponse parseResponse(String json, String outputFormat) {
        if (StringUtil.noText(json)) return ImageResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return ImageResponse.error("Invalid JSON response: " + json);
        }
        if (root == null) return ImageResponse.error("Invalid JSON response: " + json);

        JSONObject errorObject = root.getJSONObject("error");
        if (errorObject != null) {
            ImageResponse error = ImageResponse.error(errorObject.getString("message"));
            error.setErrorCode(errorObject.getString("code"));
            error.setMetadataMap(root);
            return error;
        }

        ImageResponse response = new ImageResponse();
        JSONArray data = root.getJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                if (item == null) continue;
                String b64Json = item.getString("b64_json");
                String url = item.getString("url");
                if (StringUtil.hasText(b64Json)) {
                    Image image = new Image();
                    image.setB64Json(b64Json);
                    image.setMimeType(toMimeType(outputFormat));
                    response.addImage(image);
                } else if (StringUtil.hasText(url)) {
                    response.addImage(url);
                }
            }
        }
        if (response.getImages().isEmpty()) {
            ImageResponse error = ImageResponse.error("image data is empty: " + json);
            error.setMetadataMap(root);
            return error;
        }
        response.setMetadataMap(root);
        return response;
    }

    private String resolveModel(GenerateImageRequest request) {
        return request != null && StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
    }

    private String resolveSize(GenerateImageRequest request) {
        return StringUtil.hasText(request.getResolution()) ? request.getResolution() : request.getSizeString();
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    private static String toMimeType(String outputFormat) {
        if ("jpeg".equals(outputFormat) || "jpg".equals(outputFormat)) return "image/jpeg";
        if ("webp".equals(outputFormat)) return "image/webp";
        return "image/png";
    }

    private static void putIfNotNull(JSONObject object, String key, Object value) {
        if (value != null) object.put(key, value);
    }

    private static void putIfNotEmpty(JSONObject object, String key, String value) {
        if (StringUtil.hasText(value)) object.put(key, value);
    }
}

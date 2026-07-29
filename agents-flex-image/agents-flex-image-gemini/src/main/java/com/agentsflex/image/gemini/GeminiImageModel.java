/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.gemini;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.BaseImageModel;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.net.URLConnection;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini generateContent REST API 的 Nano Banana 图片适配器。
 */
public class GeminiImageModel extends BaseImageModel<GeminiImageModelConfig> {
    /**
     * 输出宽高比，例如 {@code 16:9}。
     */
    public static final String OPTION_ASPECT_RATIO = "aspectRatio";
    /**
     * 完整 generationConfig 扩展对象，会与模块生成的图片配置合并。
     */
    public static final String OPTION_GENERATION_CONFIG = "generationConfig";

    private final AgentsFlexHttpClient agentsFlexHttpClient;

    /**
     * 使用默认 HTTP 客户端创建 Gemini 图片模型。
     */
    public GeminiImageModel(GeminiImageModelConfig config) {
        this(config, AgentsFlexHttpClient.getDefault());
    }

    /**
     * 供同包测试注入 HTTP 客户端。
     */
    GeminiImageModel(GeminiImageModelConfig config, AgentsFlexHttpClient agentsFlexHttpClient) {
        super(config);
        if (agentsFlexHttpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.agentsFlexHttpClient = agentsFlexHttpClient;
    }

    /**
     * 同步执行文生图、图片编辑或多图融合请求。
     */
    @Override
    protected ImageResponse doGenerate(GenerateImageRequest request) {
        ImageResponse validationError = validate(request);
        if (validationError != null) return validationError;

        String model = resolveModel(request);
        try {
            JSONObject payload = buildPayload(request);
            String json = agentsFlexHttpClient.post(config.getGenerateUrl(model), headers(), payload.toJSONString());
            return parseResponse(json);
        } catch (IllegalArgumentException e) {
            return ImageResponse.error(e.getMessage());
        }
    }

    private JSONObject buildPayload(GenerateImageRequest request) {
        JSONObject payload = new JSONObject();
        if (request.getOptions() != null) payload.putAll(request.getOptions());
        payload.remove(OPTION_ASPECT_RATIO);
        payload.remove(OPTION_GENERATION_CONFIG);

        JSONArray parts = new JSONArray();
        JSONObject promptPart = new JSONObject();
        promptPart.put("text", request.getPrompt());
        parts.add(promptPart);
        addInputImages(parts, request.getInputImages());

        JSONObject content = new JSONObject();
        content.put("role", "user");
        content.put("parts", parts);
        payload.put("contents", java.util.Collections.singletonList(content));
        payload.put("generationConfig", buildGenerationConfig(request));
        return payload;
    }

    private JSONObject buildGenerationConfig(GenerateImageRequest request) {
        JSONObject generationConfig = copyObject(request.getOption(OPTION_GENERATION_CONFIG));
        if (!generationConfig.containsKey("responseModalities")) {
            generationConfig.put("responseModalities", java.util.Collections.singletonList("IMAGE"));
        }

        String aspectRatio = stringOption(request, OPTION_ASPECT_RATIO);
        String imageSize = request.getResolution();
        if (StringUtil.hasText(aspectRatio) || StringUtil.hasText(imageSize)) {
            JSONObject imageConfig = objectValue(generationConfig, "imageConfig");
            putIfNotEmpty(imageConfig, "aspectRatio", aspectRatio);
            if (supportsExplicitImageSize(resolveModel(request))) {
                putIfNotEmpty(imageConfig, "imageSize", imageSize);
            }
            generationConfig.put("imageConfig", imageConfig);
        }
        return generationConfig;
    }

    private void addInputImages(JSONArray parts, List<Image> inputImages) {
        if (inputImages == null) return;
        for (Image image : inputImages) {
            JSONObject inlineData = toInlineData(image);
            JSONObject part = new JSONObject();
            part.put("inlineData", inlineData);
            parts.add(part);
        }
    }

    private JSONObject toInlineData(Image image) {
        if (image == null) throw new IllegalArgumentException("input image must not be null");
        String mimeType = StringUtil.hasText(image.getMimeType()) ? image.getMimeType() : null;
        String data;
        if (image.getBytes() != null && image.getBytes().length > 0) {
            data = Base64.getEncoder().encodeToString(image.getBytes());
        } else if (StringUtil.hasText(image.getB64Json())) {
            data = image.getB64Json();
        } else if (StringUtil.hasText(image.getUrl())) {
            byte[] bytes = agentsFlexHttpClient.getBytes(image.getUrl());
            data = Base64.getEncoder().encodeToString(bytes);
            if (StringUtil.noText(mimeType)) mimeType = guessMimeType(image.getUrl());
        } else {
            throw new IllegalArgumentException("input image must contain a URL, bytes, or b64Json");
        }
        if (StringUtil.noText(mimeType)) mimeType = "image/png";

        JSONObject inlineData = new JSONObject();
        inlineData.put("mimeType", mimeType);
        inlineData.put("data", data);
        return inlineData;
    }

    private ImageResponse validate(GenerateImageRequest request) {
        if (request == null) return ImageResponse.error("request must not be null");
        String model = resolveModel(request);
        if (StringUtil.noText(model)) return ImageResponse.error("image model must not be empty");
        if (StringUtil.noText(request.getPrompt())) return ImageResponse.error("prompt must not be empty");
        if (StringUtil.hasText(request.getSizeString())) {
            return ImageResponse.error("Gemini image generation uses resolution and aspectRatio instead of pixel size");
        }
        if (request.getN() != null && request.getN() != 1) {
            return ImageResponse.error("Gemini does not support requesting an exact image count");
        }
        if (StringUtil.hasText(request.getQuality()) || StringUtil.hasText(request.getStyle()) ||
            StringUtil.hasText(request.getResponseFormat()) || StringUtil.hasText(request.getOutputFormat())) {
            return ImageResponse.error("quality, style, responseFormat, and outputFormat are not supported by Gemini");
        }
        if (request.getSequentialGeneration() != null || request.getMaxImages() != null) {
            return ImageResponse.error("sequential generation options are not supported by Gemini");
        }
        List<Image> inputImages = request.getInputImages();
        int maxInputImages = maxInputImages(model);
        if (inputImages != null && inputImages.size() > maxInputImages) {
            return ImageResponse.error(model + " supports at most " + maxInputImages + " input images");
        }
        return null;
    }

    private ImageResponse parseResponse(String json) {
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
            error.setErrorCode(errorObject.getString("status"));
            error.setMetadataMap(root);
            return error;
        }

        ImageResponse response = new ImageResponse();
        JSONArray candidates = root.getJSONArray("candidates");
        if (candidates != null) {
            for (int i = 0; i < candidates.size(); i++) {
                JSONObject candidate = candidates.getJSONObject(i);
                JSONObject content = candidate == null ? null : candidate.getJSONObject("content");
                JSONArray parts = content == null ? null : content.getJSONArray("parts");
                addResponseImages(response, parts);
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

    private void addResponseImages(ImageResponse response, JSONArray parts) {
        if (parts == null) return;
        for (int i = 0; i < parts.size(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part == null) continue;
            JSONObject inlineData = part.getJSONObject("inlineData");
            if (inlineData == null) inlineData = part.getJSONObject("inline_data");
            if (inlineData == null || StringUtil.noText(inlineData.getString("data"))) continue;
            Image image = new Image();
            image.setB64Json(inlineData.getString("data"));
            String mimeType = inlineData.getString("mimeType");
            if (StringUtil.noText(mimeType)) mimeType = inlineData.getString("mime_type");
            image.setMimeType(mimeType);
            response.addImage(image);
        }
    }

    private String resolveModel(GenerateImageRequest request) {
        return request != null && StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
    }

    @Override
    protected String resolveAspectRatioForValidation(GenerateImageRequest request) {
        return stringOption(request, OPTION_ASPECT_RATIO);
    }

    @Override
    protected String resolveResolutionForValidation(GenerateImageRequest request) {
        return request.getResolution();
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("x-goog-api-key", config.getApiKey());
        return headers;
    }

    private static int maxInputImages(String model) {
        if (GeminiImageModels.GEMINI_2_5_FLASH_IMAGE.equals(model)) return 3;
        if (GeminiImageModels.GEMINI_3_1_FLASH_IMAGE.equals(model) ||
            GeminiImageModels.GEMINI_3_1_FLASH_LITE_IMAGE.equals(model) ||
            GeminiImageModels.GEMINI_3_PRO_IMAGE.equals(model)) return 14;
        return Integer.MAX_VALUE;
    }

    private static boolean supportsExplicitImageSize(String model) {
        return !GeminiImageModels.GEMINI_2_5_FLASH_IMAGE.equals(model) &&
            !GeminiImageModels.GEMINI_3_1_FLASH_LITE_IMAGE.equals(model);
    }

    private static String stringOption(GenerateImageRequest request, String name) {
        Object value = request.getOption(name);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static JSONObject copyObject(Object value) {
        JSONObject result = new JSONObject();
        if (value instanceof Map) result.putAll((Map<String, Object>) value);
        return result;
    }

    private static JSONObject objectValue(JSONObject parent, String key) {
        Object value = parent.get(key);
        JSONObject result = copyObject(value);
        parent.put(key, result);
        return result;
    }

    private static String guessMimeType(String url) {
        String normalized = url;
        int query = normalized.indexOf('?');
        if (query >= 0) normalized = normalized.substring(0, query);
        String mimeType = URLConnection.guessContentTypeFromName(normalized);
        return mimeType == null ? "image/jpeg" : mimeType;
    }

    private static void putIfNotEmpty(JSONObject object, String key, String value) {
        if (StringUtil.hasText(value)) object.put(key, value);
    }
}

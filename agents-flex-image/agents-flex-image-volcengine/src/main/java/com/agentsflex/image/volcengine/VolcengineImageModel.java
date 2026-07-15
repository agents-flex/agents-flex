/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.volcengine;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.BaseImageModel;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adapter for Volcengine's OpenAI-style synchronous Images API. */
public class VolcengineImageModel extends BaseImageModel<VolcengineImageModelConfig> {
    private final HttpClient httpClient;

    public VolcengineImageModel(VolcengineImageModelConfig config) {
        this(config, new HttpClient());
    }

    VolcengineImageModel(VolcengineImageModelConfig config, HttpClient httpClient) {
        super(config);
        this.httpClient = httpClient;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        if (request == null) return ImageResponse.error("request must not be null");
        String model = StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
        if (StringUtil.noText(model)) return ImageResponse.error("image model must not be empty");

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        putIfNotEmpty(payload, "prompt", request.getPrompt());
        putImages(payload, request.getInputImages());
        putIfNotEmpty(payload, "size", StringUtil.hasText(request.getResolution())
            ? request.getResolution() : request.getSizeString());
        putIfNotEmpty(payload, "response_format", request.getResponseFormat());
        putIfNotEmpty(payload, "output_format", request.getOutputFormat());
        putIfNotNull(payload, "watermark", request.getWatermark());
        putIfNotNull(payload, "seed", request.getSeed());
        putIfNotNull(payload, "optimize_prompt", request.getPromptExtend());
        if (request.getSequentialGeneration() != null) {
            payload.put("sequential_image_generation", request.getSequentialGeneration() ? "auto" : "disabled");
        }
        if (request.getMaxImages() != null) {
            JSONObject options = new JSONObject();
            options.put("max_images", request.getMaxImages());
            payload.put("sequential_image_generation_options", options);
        }
        payload.put("stream", false);
        if (request.getOptions() != null) payload.putAll(request.getOptions());

        String json = httpClient.post(config.getFullUrl(), headers(), payload.toJSONString());
        return parseResponse(json, request.getOutputFormat());
    }

    private void putImages(JSONObject payload, List<Image> inputImages) {
        if (inputImages == null || inputImages.isEmpty()) return;
        List<String> values = new ArrayList<>();
        for (Image image : inputImages) {
            if (image != null && StringUtil.hasText(image.getUrlOrBase64())) values.add(image.getUrlOrBase64());
        }
        if (values.size() == 1) payload.put("image", values.get(0));
        else if (!values.isEmpty()) payload.put("image", values);
    }

    private ImageResponse parseResponse(String json, String outputFormat) {
        if (StringUtil.noText(json)) return ImageResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return ImageResponse.error("Invalid JSON response: " + json);
        }
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
                String url = item.getString("url");
                String b64 = item.getString("b64_json");
                if (StringUtil.hasText(url)) response.addImage(url);
                else if (StringUtil.hasText(b64)) {
                    String format = StringUtil.hasText(outputFormat) ? outputFormat : "jpeg";
                    response.addImage(Base64.getDecoder().decode(b64), "image/" + format);
                }
            }
        }
        if (response.getImages().isEmpty()) return ImageResponse.error("image data is empty: " + json);
        response.setMetadataMap(root);
        return response;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    private static void putIfNotNull(JSONObject object, String key, Object value) {
        if (value != null) object.put(key, value);
    }

    private static void putIfNotEmpty(JSONObject object, String key, String value) {
        if (StringUtil.hasText(value)) object.put(key, value);
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.volcengine;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
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

/**
 * 火山引擎方舟图片模型适配器。
 * <p>通过 OpenAI 风格的同步 Images API 统一处理文生图、参考图生成、编辑和组图生成。</p>
 */
public class VolcengineImageModel extends BaseImageModel<VolcengineImageModelConfig> {
    /** 执行方舟 HTTP 请求的客户端；测试可通过包级构造方法注入替身。 */
    private final AgentsFlexHttpClient agentsFlexHttpClient;

    /** 使用默认 HTTP 客户端创建火山引擎图片模型。 */
    public VolcengineImageModel(VolcengineImageModelConfig config) {
        this(config, new AgentsFlexHttpClient());
    }

    /** 供同包测试注入 HTTP 客户端，避免单元测试访问真实服务。 */
    VolcengineImageModel(VolcengineImageModelConfig config, AgentsFlexHttpClient agentsFlexHttpClient) {
        super(config);
        this.agentsFlexHttpClient = agentsFlexHttpClient;
    }

    /**
     * 同步生成或编辑图片。
     * <p>将统一请求映射到方舟 Images API，并强制关闭流式响应以返回完整的最终图片。</p>
     */
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

        String json = agentsFlexHttpClient.post(config.getFullUrl(), headers(), payload.toJSONString());
        return parseResponse(json, request.getOutputFormat());
    }

    /**
     * 将输入图片写入方舟请求体。
     * <p>单图使用字符串形式，多图使用数组形式；URL、字节和 Base64 均由 {@link Image#getUrlOrBase64()} 统一转换。</p>
     */
    private void putImages(JSONObject payload, List<Image> inputImages) {
        if (inputImages == null || inputImages.isEmpty()) return;
        List<String> values = new ArrayList<>();
        for (Image image : inputImages) {
            if (image != null && StringUtil.hasText(image.getUrlOrBase64())) values.add(image.getUrlOrBase64());
        }
        if (values.size() == 1) payload.put("image", values.get(0));
        else if (!values.isEmpty()) payload.put("image", values);
    }

    /**
     * 解析方舟 URL/Base64 图片响应及 OpenAI 风格错误对象。
     *
     * @param outputFormat Base64 解码后用于构造 MIME 类型的文件格式
     */
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

    /** 构建 JSON Content-Type 与 Bearer Token 鉴权请求头。 */
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

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.gitee;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.BaseImageModel;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synchronous adapter for Gitee AI image generation and editing. */
public class GiteeImageModel extends BaseImageModel<GiteeImageModelConfig> {
    public static final String OPTION_MASK = "mask";
    public static final String OPTION_TASK_TYPES = "task_types";

    private final HttpClient httpClient;

    public GiteeImageModel(GiteeImageModelConfig config) {
        this(config, new HttpClient());
    }

    GiteeImageModel(GiteeImageModelConfig config, HttpClient httpClient) {
        super(config);
        if (httpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.httpClient = httpClient;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        ImageResponse validationError = validate(request);
        if (validationError != null) return validationError;

        List<Image> inputImages = request.getInputImages();
        try {
            String responseJson = inputImages == null || inputImages.isEmpty()
                ? generateImage(request)
                : editImage(request, inputImages.get(0));
            return parseResponse(responseJson);
        } catch (IllegalArgumentException e) {
            return ImageResponse.error(e.getMessage());
        }
    }

    private String generateImage(GenerateImageRequest request) {
        JSONObject payload = new JSONObject();
        copyOptions(request, payload, false);
        putStandardFields(request, payload);
        return httpClient.post(config.getFullUrl(), headers(true), payload.toJSONString());
    }

    private String editImage(GenerateImageRequest request, Image inputImage) {
        Map<String, Object> payload = new HashMap<>();
        copyOptions(request, payload, true);
        payload.put("model", resolveModel(request));
        putIfNotEmpty(payload, "prompt", request.getPrompt());
        putIfNotEmpty(payload, "size", request.getSizeString());
        putIfNotEmpty(payload, "user", request.getUser());
        putIfNotNull(payload, "n", request.getN());
        putIfNotEmpty(payload, "response_format", request.getResponseFormat());
        payload.put("image", toMultipartImage(inputImage));

        Object mask = request.getOption(OPTION_MASK);
        if (mask != null) payload.put(OPTION_MASK, toMultipartValue(mask, OPTION_MASK));
        normalizeTaskTypes(payload);
        return httpClient.multipartString(config.getEditUrl(), headers(false), payload);
    }

    private ImageResponse validate(GenerateImageRequest request) {
        if (request == null) return ImageResponse.error("request must not be null");
        if (StringUtil.noText(resolveModel(request))) {
            return ImageResponse.error("image model must not be empty");
        }
        if (request.getPrompt() != null && request.getPrompt().length() > 2000) {
            return ImageResponse.error("prompt must not exceed 2000 characters");
        }
        String responseFormat = request.getResponseFormat();
        if (StringUtil.hasText(responseFormat) &&
            !"url".equals(responseFormat) && !"b64_json".equals(responseFormat)) {
            return ImageResponse.error("responseFormat must be url or b64_json");
        }

        List<Image> inputImages = request.getInputImages();
        boolean editing = inputImages != null && !inputImages.isEmpty();
        if (editing && inputImages.size() > 1) {
            return ImageResponse.error("Gitee image editing accepts exactly one input image");
        }
        if (editing && !hasImageContent(inputImages.get(0))) {
            return ImageResponse.error("input image must contain a URL, bytes, or b64Json");
        }
        Integer n = request.getN();
        if (editing && n != null && n != 1) {
            return ImageResponse.error("Gitee image editing only supports n=1");
        }
        if (!editing && n != null && (n < 1 || n > 4)) {
            return ImageResponse.error("Gitee image generation supports n between 1 and 4");
        }

        Object mask = request.getOption(OPTION_MASK);
        if (mask != null && !editing) {
            return ImageResponse.error("mask requires an input image");
        }
        if (mask != null && !isMultipartValue(mask)) {
            return ImageResponse.error("mask must be an Image, URL, File, InputStream, or byte array");
        }
        Object taskTypes = request.getOption(OPTION_TASK_TYPES);
        if (taskTypes != null && editing && !isValidTaskType(taskTypes)) {
            return ImageResponse.error("task_types must contain exactly one of: ip, id, style");
        }
        return null;
    }

    private void putStandardFields(GenerateImageRequest request, Map<String, Object> payload) {
        payload.put("model", resolveModel(request));
        putIfNotEmpty(payload, "prompt", request.getPrompt());
        putIfNotEmpty(payload, "size", request.getSizeString());
        putIfNotEmpty(payload, "user", request.getUser());
        putIfNotNull(payload, "n", request.getN());
        putIfNotEmpty(payload, "response_format", request.getResponseFormat());
    }

    private void copyOptions(GenerateImageRequest request, Map<String, Object> payload, boolean editing) {
        if (request.getOptions() == null) return;
        for (Map.Entry<String, Object> entry : request.getOptions().entrySet()) {
            if (OPTION_MASK.equals(entry.getKey())) continue;
            if (!editing && OPTION_TASK_TYPES.equals(entry.getKey())) continue;
            payload.put(entry.getKey(), entry.getValue());
        }
    }

    private String resolveModel(GenerateImageRequest request) {
        return StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
    }

    private Object toMultipartImage(Image image) {
        if (StringUtil.hasText(image.getUrl())) return image.getUrl();
        if (image.getBytes() != null && image.getBytes().length > 0) return image.getBytes();
        return Base64.getDecoder().decode(image.getB64Json());
    }

    private Object toMultipartValue(Object value, String fieldName) {
        if (!(value instanceof Image)) return value;
        Image image = (Image) value;
        if (!hasImageContent(image)) {
            throw new IllegalArgumentException(fieldName + " image has no content");
        }
        return toMultipartImage(image);
    }

    private void normalizeTaskTypes(Map<String, Object> payload) {
        Object value = payload.get(OPTION_TASK_TYPES);
        if (!(value instanceof List)) return;
        List<?> values = (List<?>) value;
        if (values.size() != 1) {
            throw new IllegalArgumentException("Gitee image editing requires exactly one task type");
        }
        payload.put(OPTION_TASK_TYPES, values.get(0));
    }

    private ImageResponse parseResponse(String responseJson) {
        if (StringUtil.noText(responseJson)) return ImageResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(responseJson);
        } catch (Exception e) {
            return ImageResponse.error("Invalid JSON response: " + responseJson);
        }
        if (root == null) return ImageResponse.error("Invalid JSON response: " + responseJson);

        JSONObject errorObject = root.getJSONObject("error");
        if (errorObject != null) {
            ImageResponse response = ImageResponse.error(errorObject.getString("message"));
            response.setErrorCode(errorObject.getString("code"));
            response.setMetadataMap(root);
            return response;
        }

        ImageResponse response = new ImageResponse();
        JSONArray data = root.getJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                if (item == null) continue;
                String url = item.getString("url");
                String b64Json = item.getString("b64_json");
                if (StringUtil.hasText(url)) response.addImage(url);
                else if (StringUtil.hasText(b64Json)) {
                    Image image = new Image();
                    image.setB64Json(b64Json);
                    response.addImage(image);
                }
            }
        }
        if (response.getImages().isEmpty()) {
            ImageResponse error = ImageResponse.error("image data is empty: " + responseJson);
            error.setMetadataMap(root);
            return error;
        }
        response.setMetadataMap(root);
        return response;
    }

    private Map<String, String> headers(boolean json) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());
        if (json) headers.put("Content-Type", "application/json");
        return headers;
    }

    private static boolean hasImageContent(Image image) {
        return image != null && (StringUtil.hasText(image.getUrl()) ||
            image.getBytes() != null && image.getBytes().length > 0 ||
            StringUtil.hasText(image.getB64Json()));
    }

    private static boolean isMultipartValue(Object value) {
        return value instanceof Image || value instanceof String || value instanceof File ||
            value instanceof InputStream || value instanceof byte[];
    }

    private static boolean isValidTaskType(Object value) {
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            return values.size() == 1 && isTaskType(values.get(0));
        }
        return isTaskType(value);
    }

    private static boolean isTaskType(Object value) {
        return "ip".equals(value) || "id".equals(value) || "style".equals(value);
    }

    private static void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) payload.put(key, value);
    }

    private static void putIfNotEmpty(Map<String, Object> payload, String key, String value) {
        if (StringUtil.hasText(value)) payload.put(key, value);
    }
}

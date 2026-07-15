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

/**
 * Gitee AI 图片生成与编辑适配器。
 * <p>未提供输入图片时调用 JSON 格式的文生图接口；提供一张输入图片时调用 Multipart 图片编辑接口。</p>
 */
public class GiteeImageModel extends BaseImageModel<GiteeImageModelConfig> {
    /** 在 {@link GenerateImageRequest#getOptions()} 中传递编辑掩膜的参数名。 */
    public static final String OPTION_MASK = "mask";
    /** 在 {@link GenerateImageRequest#getOptions()} 中传递 DreamO 任务类型的参数名。 */
    public static final String OPTION_TASK_TYPES = "task_types";

    /** 执行 Gitee AI HTTP 请求的客户端；测试可通过包级构造方法注入替身。 */
    private final HttpClient httpClient;

    /** 使用默认 HTTP 客户端创建 Gitee AI 图片模型。 */
    public GiteeImageModel(GiteeImageModelConfig config) {
        this(config, new HttpClient());
    }

    /** 供同包测试注入 HTTP 客户端，避免单元测试访问真实服务。 */
    GiteeImageModel(GiteeImageModelConfig config, HttpClient httpClient) {
        super(config);
        if (httpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.httpClient = httpClient;
    }

    /**
     * 同步生成或编辑图片。
     * <p>方法先完成公共参数校验，再根据是否存在输入图片选择文生图或编辑端点。</p>
     */
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

    /** 构建 JSON 文生图请求并调用 {@code /v1/images/generations}。 */
    private String generateImage(GenerateImageRequest request) {
        JSONObject payload = new JSONObject();
        copyOptions(request, payload, false);
        putStandardFields(request, payload);
        return httpClient.post(config.getFullUrl(), headers(true), payload.toJSONString());
    }

    /**
     * 构建 Multipart 图片编辑请求并调用 {@code /v1/images/edits}。
     * <p>输入图片和掩膜既可以使用远程 URL，也可以上传本地二进制内容。</p>
     */
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

    /**
     * 校验 Gitee 接口的模型、输出格式、图片数量、掩膜和 DreamO 任务类型约束。
     *
     * @return 校验失败响应；校验通过时返回 {@code null}
     */
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

    /** 将核心请求中的通用字段写入 Gitee 请求载荷。 */
    private void putStandardFields(GenerateImageRequest request, Map<String, Object> payload) {
        payload.put("model", resolveModel(request));
        putIfNotEmpty(payload, "prompt", request.getPrompt());
        putIfNotEmpty(payload, "size", request.getSizeString());
        putIfNotEmpty(payload, "user", request.getUser());
        putIfNotNull(payload, "n", request.getN());
        putIfNotEmpty(payload, "response_format", request.getResponseFormat());
    }

    /**
     * 透传供应商扩展参数。
     * <p>掩膜会单独转换为 Multipart 值，task_types 仅允许出现在编辑请求中。</p>
     */
    private void copyOptions(GenerateImageRequest request, Map<String, Object> payload, boolean editing) {
        if (request.getOptions() == null) return;
        for (Map.Entry<String, Object> entry : request.getOptions().entrySet()) {
            if (OPTION_MASK.equals(entry.getKey())) continue;
            if (!editing && OPTION_TASK_TYPES.equals(entry.getKey())) continue;
            payload.put(entry.getKey(), entry.getValue());
        }
    }

    /** 获取本次请求的模型，请求级模型优先于配置默认模型。 */
    private String resolveModel(GenerateImageRequest request) {
        return StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
    }

    /** 将 URL、字节或 Base64 图片转换为 Multipart 可接受的值。 */
    private Object toMultipartImage(Image image) {
        if (StringUtil.hasText(image.getUrl())) return image.getUrl();
        if (image.getBytes() != null && image.getBytes().length > 0) return image.getBytes();
        return Base64.getDecoder().decode(image.getB64Json());
    }

    /** 转换掩膜等可能以 {@link Image} 表达的 Multipart 字段。 */
    private Object toMultipartValue(Object value, String fieldName) {
        if (!(value instanceof Image)) return value;
        Image image = (Image) value;
        if (!hasImageContent(image)) {
            throw new IllegalArgumentException(fieldName + " image has no content");
        }
        return toMultipartImage(image);
    }

    /**
     * 将单元素 task_types 列表规范化为 Multipart 表单值。
     * <p>Gitee 编辑接口当前只支持单图单结果，因此任务类型也只能指定一个。</p>
     */
    private void normalizeTaskTypes(Map<String, Object> payload) {
        Object value = payload.get(OPTION_TASK_TYPES);
        if (!(value instanceof List)) return;
        List<?> values = (List<?>) value;
        if (values.size() != 1) {
            throw new IllegalArgumentException("Gitee image editing requires exactly one task type");
        }
        payload.put(OPTION_TASK_TYPES, values.get(0));
    }

    /** 解析 OpenAI 风格的 URL/Base64 图片响应及嵌套错误对象。 */
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

    /**
     * 构建鉴权请求头。
     * <p>JSON 请求显式声明 Content-Type；Multipart 请求由 HTTP 客户端生成带 boundary 的 Content-Type。</p>
     */
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

/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.ocr.baidu;

import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.model.ocr.BaseOcrModel;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;

/**
 * 百度 PaddleOCR-VL 异步文档解析适配器。
 *
 * <p>提交接口采用 {@code application/x-www-form-urlencoded}：远程文件通过
 * {@code file_url} 传递，本地文件则整体读取后以 Base64 放入 {@code file_data}。
 * 提交成功后使用返回的 task_id 查询结果。</p>
 */
public class BaiduOcrModel extends BaseOcrModel<BaiduOcrConfig> {
    /**
     * 执行百度 HTTP 请求的客户端。
     */
    private final OkHttpClient httpClient;

    /**
     * 使用默认 HTTP 客户端创建百度 OCR 模型。
     */
    public BaiduOcrModel(BaiduOcrConfig config) {
        this(config, OkHttpClientUtil.buildDefaultClient());
    }

    /**
     * 供同包测试注入可控 HTTP 客户端。
     */
    BaiduOcrModel(BaiduOcrConfig config, OkHttpClient httpClient) {
        super(config);
        if (httpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.httpClient = httpClient;
    }

    /**
     * 提交百度异步识别任务。
     *
     * @param request URL 或本地文件请求；URL 输入必须能推导或显式指定文件名
     */
    @Override
    public OcrResponse recognize(OcrRequest request) {
        OcrResponse error = validateRequest(request);
        if (error != null) return error;
        if (StringUtil.noText(config.getApiKey())) return OcrResponse.error("Baidu access token must not be empty");
        String fileName = resolveFileName(request);
        if (StringUtil.noText(fileName)) return OcrResponse.error("fileName is required for Baidu OCR URL input");

        FormBody.Builder form = new FormBody.Builder();
        try {
            if (request.getFile() != null) {
                // 百度此接口不接收 multipart 文件，必须将完整文件编码为 Base64 表单字段。
                form.add("file_data", Base64.getEncoder().encodeToString(Files.readAllBytes(request.getFile().toPath())));
            } else {
                form.add("file_url", request.getFileUrl());
            }
        } catch (IOException e) {
            return OcrResponse.error("Failed to read OCR input file: " + e.getMessage());
        }
        form.add("file_name", fileName);
        // 公共输入字段由适配器控制，附加选项只能补充供应商扩展参数。
        addOptions(form, request.getOptions());
        return execute(config.getFullUrl(), form.build(), true);
    }

    /** 使用 task_id 查询百度任务当前状态和结果资源。 */
    @Override
    public OcrResponse getResult(String taskId) {
        if (StringUtil.noText(taskId)) return OcrResponse.error("taskId must not be empty");
        if (StringUtil.noText(config.getApiKey())) return OcrResponse.error("Baidu access token must not be empty");
        return execute(config.getQueryUrl(), new FormBody.Builder().add("task_id", taskId).build(), false);
    }

    /**
     * 执行表单 POST，并将 HTTP、网络和协议错误统一转换为 OcrResponse。
     */
    private OcrResponse execute(String url, FormBody body, boolean submitted) {
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
        // bce-v3 API Key 必须放在 Bearer 请求头；旧 access token 已由配置追加到 URL。
        if (config.usesBearerAuthorization()) {
            requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
        }
        Request request = requestBuilder.build();
        try (Response response = httpClient.newCall(request).execute(); ResponseBody responseBody = response.body()) {
            String content = responseBody == null ? null : responseBody.string();
            if (!response.isSuccessful()) return OcrResponse.error("HTTP " + response.code(), content);
            return parseResponse(content, submitted);
        } catch (IOException e) {
            return OcrResponse.error("Baidu OCR request failed: " + e.getMessage());
        }
    }

    /**
     * 解析百度响应并映射为统一任务状态。
     *
     * @param json      百度原始 JSON
     * @param submitted 是否为提交接口响应
     */
    static OcrResponse parseResponse(String json, boolean submitted) {
        if (StringUtil.noText(json)) return OcrResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return OcrResponse.error("Invalid JSON response: " + json);
        }
        int errorCode = root.getIntValue("error_code");
        if (errorCode != 0) return OcrResponse.error(String.valueOf(errorCode), root.getString("error_msg"));
        JSONObject result = root.getJSONObject("result");
        if (result == null) return OcrResponse.error("Baidu OCR response result is empty");

        OcrResponse response = new OcrResponse();
        response.setTaskId(result.getString("task_id"));
        // 某些成功提交响应没有 status，此时不能误判为 UNKNOWN，否则轮询语义不清晰。
        response.setStatus(submitted && result.getString("status") == null
            ? OcrTaskStatus.SUBMITTED : mapStatus(result.getString("status")));
        addResource(response, "markdown", result.getString("markdown_url"));
        addResource(response, "json", result.getString("parse_result_url"));
        if (response.getStatus() == OcrTaskStatus.FAILED) {
            response.setError(true);
            response.setErrorMessage(result.getString("task_error"));
        }
        setProviderMetadata(response, root);
        return response;
    }

    /**
     * 将百度状态字符串转换为供应商无关状态。
     */
    private static OcrTaskStatus mapStatus(String status) {
        if (status == null) return OcrTaskStatus.UNKNOWN;
        String value = status.toLowerCase();
        if ("pending".equals(value)) return OcrTaskStatus.QUEUED;
        if ("processing".equals(value)) return OcrTaskStatus.RUNNING;
        if ("success".equals(value)) return OcrTaskStatus.SUCCEEDED;
        if ("failed".equals(value)) return OcrTaskStatus.FAILED;
        return OcrTaskStatus.UNKNOWN;
    }

    /**
     * 仅添加供应商实际返回的非空资源地址。
     */
    private static void addResource(OcrResponse response, String type, String url) {
        if (StringUtil.hasText(url)) response.addResource(type, url);
    }

    /**
     * 添加供应商扩展表单参数，同时阻止调用方覆盖适配器维护的文件字段。
     */
    private static void addOptions(FormBody.Builder form, Map<String, Object> options) {
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null &&
                !"file_data".equals(entry.getKey()) && !"file_url".equals(entry.getKey()) &&
                !"file_name".equals(entry.getKey())) {
                form.add(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
    }

    /**
     * 解析百度必需的文件名：优先使用显式值，其次从 URL 路径末段推导。
     */
    private static String resolveFileName(OcrRequest request) {
        if (StringUtil.hasText(request.getFileName())) return request.getFileName();
        if (StringUtil.noText(request.getFileUrl())) return null;
        try {
            String path = URI.create(request.getFileUrl()).getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return StringUtil.hasText(name) ? name : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

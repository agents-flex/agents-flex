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
package com.agentsflex.ocr.somark;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.ocr.BaseOcrModel;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResource;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * SoMark 同步及异步文档解析适配器。
 *
 * <p>{@link #recognize(OcrRequest)} 提交异步任务，以便复用 Agents-Flex 的统一轮询接口；
 * 需要在当前请求中直接等待供应商结果时可调用 {@link #recognizeSync(OcrRequest)}。</p>
 */
public class SomarkOcrModel extends BaseOcrModel<SomarkOcrConfig> {
    private static final String ELEMENT_FORMATS = "element_formats";
    private static final String FEATURE_CONFIG = "feature_config";

    /**
     * AgentsFlex 统一 HTTP 客户端。
     */
    private final AgentsFlexHttpClient httpClient;

    /**
     * 使用默认 HTTP 客户端创建 SoMark OCR 模型。
     */
    public SomarkOcrModel(SomarkOcrConfig config) {
        this(config, AgentsFlexHttpClient.getDefault());
    }

    /**
     * 供同包测试注入可控 HTTP 客户端。
     */
    SomarkOcrModel(SomarkOcrConfig config, AgentsFlexHttpClient httpClient) {
        super(config);
        if (httpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.httpClient = httpClient;
    }

    /**
     * 通过 {@code /parse/async} 提交异步解析任务。
     */
    @Override
    public OcrResponse recognize(OcrRequest request) {
        OcrResponse error = validate(request);
        if (error != null) return error;
        return parseResponse(httpClient.multipartString(
            config.getFullUrl(), Collections.emptyMap(), requestPayload(request)), true);
    }

    /**
     * 通过 {@code /parse/sync} 同步解析文档并直接返回结果。
     */
    public OcrResponse recognizeSync(OcrRequest request) {
        OcrResponse error = validate(request);
        if (error != null) return error;
        OcrResponse response = parseResponse(httpClient.multipartString(
            config.getSyncUrl(), Collections.emptyMap(), requestPayload(request)), false);
        return resolveMarkdownWhenAvailable(response);
    }

    /**
     * 通过 {@code /parse/async_check} 查询异步任务状态和结果。
     */
    @Override
    public OcrResponse getResult(String taskId) {
        if (StringUtil.noText(taskId)) return OcrResponse.error("taskId must not be empty");
        OcrResponse credentialError = validateApiKey();
        if (credentialError != null) return credentialError;
        Map<String, Object> payload = new HashMap<>();
        payload.put("task_id", taskId);
        payload.put("api_key", config.getApiKey());
        OcrResponse response = parseResponse(httpClient.multipartString(
            config.getQueryUrl(taskId), Collections.emptyMap(), payload), false);
        if (response != null && StringUtil.noText(response.getTaskId())) response.setTaskId(taskId);
        return resolveMarkdownWhenAvailable(response);
    }

    private OcrResponse validate(OcrRequest request) {
        OcrResponse error = validateRequest(request);
        return error == null ? validateApiKey() : error;
    }

    private OcrResponse validateApiKey() {
        return StringUtil.noText(config.getApiKey()) ? OcrResponse.error("apiKey must not be empty") : null;
    }

    /**
     * 构造 SoMark multipart 表单。对象配置按官方协议序列化为 JSON，输出格式集合由
     * HTTP 客户端展开为同名的重复表单字段。
     */
    private Map<String, Object> requestPayload(OcrRequest request) {
        Map<String, Object> payload = new HashMap<>();
        for (Map.Entry<String, Object> entry : request.getOptions().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if ("api_key".equals(entry.getKey()) || "file".equals(entry.getKey())
                || "file_url".equals(entry.getKey())) continue;
            payload.put(entry.getKey(), normalizeOption(entry.getKey(), entry.getValue()));
        }
        payload.put("api_key", config.getApiKey());
        if (request.getFile() != null) payload.put("file", request.getFile());
        else payload.put("file_url", request.getFileUrl());
        return payload;
    }

    private static Object normalizeOption(String key, Object value) {
        if ((ELEMENT_FORMATS.equals(key) || FEATURE_CONFIG.equals(key)) && !(value instanceof CharSequence)) {
            return JSON.toJSONString(value);
        }
        return value;
    }

    /**
     * 将 SoMark 的统一响应结构映射为 OCR 公共响应。
     */
    static OcrResponse parseResponse(String json, boolean submitted) {
        if (StringUtil.noText(json)) return OcrResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return OcrResponse.error("Invalid JSON response: " + json);
        }
        Integer code = root.getInteger("code");
        if (code != null && code != 0) {
            return OcrResponse.error(String.valueOf(code), root.getString("message"));
        }
        JSONObject data = root.getJSONObject("data");
        if (data == null) return OcrResponse.error(code == null ? null : String.valueOf(code),
            firstText(root, "message", "error"));

        OcrResponse response = new OcrResponse();
        response.setTaskId(data.getString("task_id"));
        response.setStatus(mapStatus(data.getString("status")));
        JSONObject result = data.getJSONObject("result");
        if (result != null) parseResult(response, result);

        if (response.getStatus() == OcrTaskStatus.UNKNOWN) {
            response.setStatus(submitted ? OcrTaskStatus.SUBMITTED
                : result == null ? OcrTaskStatus.UNKNOWN : OcrTaskStatus.SUCCEEDED);
        }
        if (response.getStatus() == OcrTaskStatus.FAILED) {
            response.setError(true);
            Object taskError = data.get("error");
            if (taskError instanceof JSONObject) {
                JSONObject error = (JSONObject) taskError;
                response.setErrorCode(firstText(error, "code", "error_code", "type"));
                response.setErrorMessage(firstText(error, "message", "error_message", "detail"));
            } else if (taskError != null) {
                response.setErrorMessage(String.valueOf(taskError));
            }
            if (StringUtil.noText(response.getErrorMessage())) response.setErrorMessage("SoMark OCR task failed");
        }
        setProviderMetadata(response, root);
        return response;
    }

    private static void parseResult(OcrResponse response, JSONObject result) {
        JSONObject outputs = result.getJSONObject("outputs");
        if (outputs == null) return;
        response.setMarkdown(outputs.getString("markdown"));
        addResource(response, "archive", outputs.getString("zip"));
        Object jsonOutput = outputs.get("json");
        if (jsonOutput instanceof String) addResource(response, "json", (String) jsonOutput);
    }

    private OcrResponse resolveMarkdownWhenAvailable(OcrResponse response) {
        if (response == null || response.isError() || response.getStatus() != OcrTaskStatus.SUCCEEDED) return response;
        if (StringUtil.hasText(response.getMarkdown())) return resolveResultMarkdown(response);
        for (OcrResource resource : response.getResources()) {
            if (resource != null && ("markdown".equalsIgnoreCase(resource.getType())
                || "archive".equalsIgnoreCase(resource.getType()))) {
                return resolveResultMarkdown(response);
            }
        }
        return response;
    }

    private static void addResource(OcrResponse response, String type, String url) {
        if (StringUtil.hasText(url)) response.addResource(type, url);
    }

    private static OcrTaskStatus mapStatus(String status) {
        if (status == null) return OcrTaskStatus.UNKNOWN;
        if ("QUEUING".equalsIgnoreCase(status)) return OcrTaskStatus.QUEUED;
        if ("PROCESSING".equalsIgnoreCase(status)) return OcrTaskStatus.RUNNING;
        if ("SUCCESS".equalsIgnoreCase(status)) return OcrTaskStatus.SUCCEEDED;
        if ("FAILED".equalsIgnoreCase(status)) return OcrTaskStatus.FAILED;
        return OcrTaskStatus.UNKNOWN;
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtil.hasText(value)) return value;
        }
        return null;
    }
}

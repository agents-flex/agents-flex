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
package com.agentsflex.ocr.mineru;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.model.ocr.BaseOcrModel;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MinerU 异步文档解析适配器。
 *
 * <p>远程 URL 可直接创建普通任务。本地文件必须先向 MinerU 申请预签名 URL，使用
 * HTTP PUT 上传文件，再通过 batch_id 查询批任务。适配器会在当前进程内记录由本地
 * 上传创建的批任务编号，以便 {@link #getResult(String)} 自动选择正确查询端点。</p>
 */
public class MineruOcrModel extends BaseOcrModel<MineruOcrConfig> {
    /**
     * 调用 MinerU JSON API 的统一客户端。
     */
    private final AgentsFlexHttpClient httpClient;
    /**
     * 向预签名 URL 上传二进制文件的 OkHttp 客户端。
     */
    private final OkHttpClient uploadClient;
    /**
     * 当前进程内通过本地文件流程创建、且尚未终结的批任务编号。
     */
    private final Set<String> batchTaskIds = ConcurrentHashMap.newKeySet();

    /**
     * 使用默认 JSON 客户端和上传客户端创建 MinerU OCR 模型。
     */
    public MineruOcrModel(MineruOcrConfig config) {
        this(config, AgentsFlexHttpClient.getDefault(), OkHttpClientUtil.buildDefaultClient());
    }

    /**
     * 供同包测试注入可控 HTTP 客户端。
     */
    MineruOcrModel(MineruOcrConfig config, AgentsFlexHttpClient httpClient, OkHttpClient uploadClient) {
        super(config);
        if (httpClient == null || uploadClient == null)
            throw new IllegalArgumentException("http clients must not be null");
        this.httpClient = httpClient;
        this.uploadClient = uploadClient;
    }

    /** 根据输入类型选择远程 URL 单任务流程或本地文件批任务流程。 */
    @Override
    public OcrResponse recognize(OcrRequest request) {
        OcrResponse error = validateRequest(request);
        if (error != null) return error;
        String model = StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
        if (request.getFileUrl() != null) {
            JSONObject payload = new JSONObject(request.getOptions());
            // 核心字段最后写入，防止扩展选项覆盖真实输入地址和模型版本。
            payload.put("url", request.getFileUrl());
            if (StringUtil.hasText(model)) payload.put("model_version", model);
            return parseResponse(httpClient.post(config.getFullUrl(), headers(true), payload.toJSONString()), true);
        }
        return submitLocalFile(request, model);
    }

    /**
     * 执行本地文件的两阶段提交：先申请预签名 URL，再直接 PUT 文件内容。
     */
    private OcrResponse submitLocalFile(OcrRequest request, String model) {
        JSONObject file = new JSONObject();
        file.put("name", request.getFileName());
        JSONObject payload = new JSONObject(request.getOptions());
        payload.put("files", new Object[]{file});
        if (StringUtil.hasText(model)) payload.put("model_version", model);
        String json = httpClient.post(config.getUploadUrl(), headers(true), payload.toJSONString());
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return OcrResponse.error("Invalid JSON response: " + json);
        }
        if (root.getIntValue("code") != 0)
            return OcrResponse.error(String.valueOf(root.getIntValue("code")), root.getString("msg"));
        JSONObject data = root.getJSONObject("data");
        JSONArray urls = data == null ? null : data.getJSONArray("file_urls");
        if (urls == null || urls.isEmpty()) return OcrResponse.error("MinerU did not return an upload URL");
        String uploadUrl = urls.getString(0);
        try {
            // 预签名地址通常不接受业务 Bearer Token，按供应商要求直接上传二进制内容。
            // 预签名内容没有包含 Content-Type，额外发送该请求头会导致 OSS 签名校验失败。
            Request upload = new Request.Builder().url(uploadUrl)
                .put(RequestBody.create(request.getFile(), null)).build();
            try (Response response = uploadClient.newCall(upload).execute()) {
                // OSS 错误正文可能包含临时 AccessKey、签名和对象路径，不向业务响应传播。
                if (!response.isSuccessful())
                    return OcrResponse.error("HTTP " + response.code(), "MinerU file upload failed");
            }
        } catch (IOException e) {
            return OcrResponse.error("MinerU file upload failed: " + e.getMessage());
        }
        OcrResponse response = new OcrResponse();
        response.setTaskId(data.getString("batch_id"));
        // 只在进程内记录路由提示；跨重启查询应显式调用 getBatchResult。
        if (StringUtil.hasText(response.getTaskId())) batchTaskIds.add(response.getTaskId());
        response.setStatus(OcrTaskStatus.SUBMITTED);
        setProviderMetadata(response, root);
        return response;
    }

    /**
     * 查询任务结果；已知批任务自动走批量查询端点，普通任务走单任务端点。
     */
    @Override
    public OcrResponse getResult(String taskId) {
        if (StringUtil.noText(taskId)) return OcrResponse.error("taskId must not be empty");
        boolean batch = batchTaskIds.contains(taskId);
        OcrResponse response = batch ? getBatchResult(taskId)
            : parseResponse(httpClient.get(config.getQueryUrl(taskId), headers(false)), false);
        // 终态任务不再需要保留进程内路由信息，及时释放集合条目。
        if (batch && response != null && response.isTerminal()) batchTaskIds.remove(taskId);
        return response;
    }

    /**
     * 查询通过本地文件上传流程创建的批任务。
     *
     * <p>应用重启后内存中的批任务路由信息会丢失，持久化 batch_id 的调用方应直接
     * 使用此方法恢复查询。</p>
     *
     * @param batchId 本地文件流程返回的批任务编号
     */
    public OcrResponse getBatchResult(String batchId) {
        if (StringUtil.noText(batchId)) return OcrResponse.error("batchId must not be empty");
        OcrResponse response = parseResponse(httpClient.get(config.getBatchQueryUrl(batchId), headers(false)), false);
        if (response != null && StringUtil.noText(response.getTaskId())) response.setTaskId(batchId);
        return response;
    }

    /**
     * 解析 MinerU 普通任务或批任务响应。
     *
     * <p>批查询可能把真实结果放在 extract_result 对象或数组中；解析时先保存外层
     * batch_id，再进入内层提取状态和资源，避免丢失可继续查询的标识。</p>
     */
    static OcrResponse parseResponse(String json, boolean submitted) {
        if (StringUtil.noText(json)) return OcrResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return OcrResponse.error("Invalid JSON response: " + json);
        }
        int code = root.getIntValue("code");
        if (code != 0) return OcrResponse.error(String.valueOf(code), root.getString("msg"));
        JSONObject data = root.getJSONObject("data");
        if (data == null) return OcrResponse.error("MinerU response data is empty");
        String containerTaskId = firstText(data, "task_id", "batch_id");
        Object extractResult = data.get("extract_result");
        // 同时兼容单对象和批量数组结构；当前单文件提交只读取数组第一项。
        if (extractResult instanceof JSONObject) {
            data = (JSONObject) extractResult;
        } else if (extractResult instanceof JSONArray && !((JSONArray) extractResult).isEmpty()) {
            JSONObject first = ((JSONArray) extractResult).getJSONObject(0);
            if (first != null) data = first;
        }
        OcrResponse response = new OcrResponse();
        response.setTaskId(StringUtil.hasText(data.getString("task_id")) ? data.getString("task_id") : containerTaskId);
        response.setStatus(submitted && data.getString("state") == null
            ? OcrTaskStatus.SUBMITTED : mapStatus(data.getString("state")));
        addResource(response, "archive", data.getString("full_zip_url"));
        addResource(response, "markdown", data.getString("markdown_url"));
        response.setMarkdown(data.getString("markdown"));
        if (response.getStatus() == OcrTaskStatus.FAILED) {
            response.setError(true);
            Object errCode = data.get("err_code");
            response.setErrorCode(errCode == null ? null : String.valueOf(errCode));
            response.setErrorMessage(data.getString("err_msg"));
        }
        setProviderMetadata(response, root);
        return response;
    }

    /**
     * 仅添加 MinerU 实际返回的结果资源。
     */
    private static void addResource(OcrResponse response, String type, String url) {
        if (StringUtil.hasText(url)) response.addResource(type, url);
    }

    /**
     * 将 MinerU 状态值映射为公共任务状态。
     */
    private static OcrTaskStatus mapStatus(String state) {
        if (state == null) return OcrTaskStatus.UNKNOWN;
        String value = state.toLowerCase();
        if ("waiting-file".equals(value) || "pending".equals(value)) return OcrTaskStatus.QUEUED;
        if ("uploading".equals(value) || "running".equals(value) || "converting".equals(value))
            return OcrTaskStatus.RUNNING;
        if ("done".equals(value)) return OcrTaskStatus.SUCCEEDED;
        if ("failed".equals(value)) return OcrTaskStatus.FAILED;
        return OcrTaskStatus.UNKNOWN;
    }

    /**
     * 构造 MinerU API 请求头；JSON 请求额外声明内容类型。
     */
    private Map<String, String> headers(boolean json) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());
        if (json) headers.put("Content-Type", "application/json");
        return headers;
    }

    /**
     * 按顺序读取第一个非空的兼容字段值。
     */
    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtil.hasText(value)) return value;
        }
        return null;
    }
}

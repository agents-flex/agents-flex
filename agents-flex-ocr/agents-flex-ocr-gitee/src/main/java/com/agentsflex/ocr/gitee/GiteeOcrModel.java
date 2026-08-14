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
package com.agentsflex.ocr.gitee;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.ocr.BaseOcrModel;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Gitee AI 异步文档解析适配器。
 *
 * <p>供应商提交接口使用 multipart/form-data。调用方传入远程 URL 时，适配器会先下载到临时文件，
 * 完成上传后立即清理；供应商返回的文本、Markdown 和下载资源会被归一化到 {@link OcrResponse}。</p>
 */
public class GiteeOcrModel extends BaseOcrModel<GiteeOcrConfig> {
    /**
     * AgentsFlex 统一 HTTP 客户端。
     */
    private final AgentsFlexHttpClient httpClient;

    /**
     * 使用默认 HTTP 客户端创建 Gitee OCR 模型。
     */
    public GiteeOcrModel(GiteeOcrConfig config) {
        this(config, AgentsFlexHttpClient.getDefault());
    }

    /**
     * 供同包测试注入可控 HTTP 客户端。
     */
    GiteeOcrModel(GiteeOcrConfig config, AgentsFlexHttpClient httpClient) {
        super(config);
        if (httpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.httpClient = httpClient;
    }

    /** 提交本地文件或远程 URL 文档解析任务。 */
    @Override
    public OcrResponse recognize(OcrRequest request) {
        OcrResponse error = validateRequest(request);
        if (error != null) return error;
        String model = StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
        if (StringUtil.noText(model)) return OcrResponse.error("OCR model must not be empty");
        File file = request.getFile();
        boolean temporary = false;
        if (file == null) {
            try {
                file = downloadTemporaryFile(request);
                temporary = true;
            } catch (RuntimeException cause) {
                return OcrResponse.error("Failed to prepare Gitee OCR input: " + cause.getMessage());
            }
        }
        try {
            // multipart 网络异常继续向上抛出，让 Async Task 记录为 SUBMIT_UNKNOWN。
            return submitFile(request, model, file);
        } finally {
            if (temporary && file != null) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException ignored) {
                    file.deleteOnExit();
                }
            }
        }
    }

    private OcrResponse submitFile(OcrRequest request, String model, File file) {
        Map<String, Object> payload = new HashMap<>();
        // 先复制扩展选项，再写入受适配器控制的 model/file，避免调用方覆盖核心字段。
        payload.putAll(request.getOptions());
        payload.put("model", model);
        payload.put("file", file);
        return parseResponse(httpClient.multipartString(config.getFullUrl(), headers(), payload), true);
    }

    private File downloadTemporaryFile(OcrRequest request) {
        if (StringUtil.noText(request.getFileUrl())) {
            throw new IllegalArgumentException("Gitee OCR requires a local file or accessible URL");
        }
        byte[] bytes = httpClient.getBytes(request.getFileUrl());
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Downloaded OCR file is empty");
        }
        String suffix = suffix(request.getFileName(), request.getFileUrl());
        try {
            File file = Files.createTempFile("agents-flex-gitee-ocr-", suffix).toFile();
            Files.write(file.toPath(), bytes);
            return file;
        } catch (IOException error) {
            throw new IllegalStateException("Can not create temporary OCR file", error);
        }
    }

    private String suffix(String fileName, String url) {
        String source = StringUtil.hasText(fileName) ? fileName : url;
        int query = source.indexOf('?');
        if (query >= 0) source = source.substring(0, query);
        int slash = source.lastIndexOf('/');
        int dot = source.lastIndexOf('.');
        return dot > slash && source.length() - dot <= 12 ? source.substring(dot) : ".tmp";
    }

    /** 使用任务编号查询 Gitee 任务状态和解析结果。 */
    @Override
    public OcrResponse getResult(String taskId) {
        if (StringUtil.noText(taskId)) return OcrResponse.error("taskId must not be empty");
        return resolveResultMarkdown(parseResponse(httpClient.get(config.getQueryUrl(taskId), headers()), false));
    }

    /**
     * 将 Gitee 提交或查询响应转换为统一 OCR 响应。
     *
     * <p>兼容 task_id/id、failure/failed 等字段和状态别名，以降低供应商接口版本
     * 差异对业务代码的影响。</p>
     */
    static OcrResponse parseResponse(String json, boolean submitted) {
        if (StringUtil.noText(json)) return OcrResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return OcrResponse.error("Invalid JSON response: " + json);
        }
        JSONObject providerError = root.getJSONObject("error");
        if (providerError != null) {
            return OcrResponse.error(firstText(providerError, "code", "type"), providerError.getString("message"));
        }
        OcrResponse response = new OcrResponse();
        response.setTaskId(firstText(root, "task_id", "id"));
        // 成功提交时供应商可能省略 status，显式标记 SUBMITTED 以允许后续轮询。
        response.setStatus(submitted && root.getString("status") == null
            ? OcrTaskStatus.SUBMITTED : mapStatus(root.getString("status")));
        JSONObject output = root.getJSONObject("output");
        if (output != null) parseOutput(response, output);
        if (response.getStatus() == OcrTaskStatus.FAILED) {
            response.setError(true);
            response.setErrorCode(firstText(root, "error_code", "code"));
            response.setErrorMessage(firstText(root, "error_message", "message"));
        }
        setProviderMetadata(response, root);
        return response;
    }

    /**
     * 提取内联内容及供应商返回的多种下载资源。
     */
    private static void parseOutput(OcrResponse response, JSONObject output) {
        response.setText(firstText(output, "text", "content"));
        response.setMarkdown(firstText(output, "markdown", "md"));
        JSONArray segments = output.getJSONArray("segments");
        if (StringUtil.noText(response.getMarkdown()) && segments != null) {
            StringBuilder markdown = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                JSONObject segment = segments.getJSONObject(i);
                String content = segment == null ? null : segment.getString("content");
                if (StringUtil.hasText(content)) {
                    if (markdown.length() > 0) markdown.append('\n');
                    markdown.append(content);
                }
            }
            if (markdown.length() > 0) response.setMarkdown(markdown.toString());
        }
        addResource(response, "markdown", firstText(output, "markdown_url", "md_url"));
        addResource(response, "json", firstText(output, "json_url", "content_list_url"));
        addResource(response, "archive", firstText(output, "full_zip_url", "zip_url"));
        JSONArray files = output.getJSONArray("files");
        // files 用于承载未来新增的结果格式，保留供应商给出的类型名称。
        if (files != null) for (int i = 0; i < files.size(); i++) {
            JSONObject file = files.getJSONObject(i);
            if (file != null)
                addResource(response, firstText(file, "type", "format"), firstText(file, "url", "file_url"));
        }
    }

    /**
     * 仅记录有效的外部资源地址。
     */
    private static void addResource(OcrResponse response, String type, String url) {
        if (StringUtil.hasText(url)) response.addResource(type, url);
    }

    /**
     * 将 Gitee 的状态别名归一化为公共状态枚举。
     */
    private static OcrTaskStatus mapStatus(String status) {
        if (status == null) return OcrTaskStatus.UNKNOWN;
        String value = status.toLowerCase();
        if ("waiting".equals(value) || "pending".equals(value) || "queued".equals(value)) return OcrTaskStatus.QUEUED;
        if ("in_progress".equals(value) || "running".equals(value) || "processing".equals(value))
            return OcrTaskStatus.RUNNING;
        if ("success".equals(value) || "succeeded".equals(value) || "done".equals(value))
            return OcrTaskStatus.SUCCEEDED;
        if ("failure".equals(value) || "failed".equals(value)) return OcrTaskStatus.FAILED;
        if ("cancelled".equals(value) || "canceled".equals(value)) return OcrTaskStatus.CANCELED;
        return OcrTaskStatus.UNKNOWN;
    }

    /**
     * 构造 Gitee Bearer Token 鉴权请求头。
     */
    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    /**
     * 按候选字段顺序返回第一个非空文本，用于兼容协议字段别名。
     */
    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtil.hasText(value)) return value;
        }
        return null;
    }
}

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
import com.agentsflex.core.model.ocr.OcrBlock;
import com.agentsflex.core.model.ocr.OcrBlockType;
import com.agentsflex.core.model.ocr.OcrBoundingBox;
import com.agentsflex.core.model.ocr.OcrCoordinateOrigin;
import com.agentsflex.core.model.ocr.OcrCoordinateUnit;
import com.agentsflex.core.model.ocr.OcrDocument;
import com.agentsflex.core.model.ocr.OcrPage;
import com.agentsflex.core.model.ocr.OcrPageSize;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResource;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
     * 将 OpenXLab AK/SK 换取为 MinerU Bearer JWT，并在内存中管理有效期。
     */
    private final OpenXlabAuthClient authClient;
    /**
     * 当前进程内通过本地文件流程创建、且尚未终结的批任务编号。
     */
    private final Set<String> batchTaskIds = ConcurrentHashMap.newKeySet();

    /**
     * 使用默认 JSON 客户端和上传客户端创建 MinerU OCR 模型。
     */
    public MineruOcrModel(MineruOcrConfig config) {
        this(config, AgentsFlexHttpClient.getDefault(), OkHttpClientUtil.buildDefaultClient(),
            new OpenXlabAuthClient(AgentsFlexHttpClient.getDefault()));
    }

    /**
     * 供同包测试注入可控 HTTP 客户端。
     */
    MineruOcrModel(MineruOcrConfig config, AgentsFlexHttpClient httpClient, OkHttpClient uploadClient) {
        this(config, httpClient, uploadClient, new OpenXlabAuthClient(httpClient));
    }

    MineruOcrModel(MineruOcrConfig config, AgentsFlexHttpClient httpClient, OkHttpClient uploadClient,
                   OpenXlabAuthClient authClient) {
        super(config);
        if (httpClient == null || uploadClient == null || authClient == null)
            throw new IllegalArgumentException("http clients must not be null");
        this.httpClient = httpClient;
        this.uploadClient = uploadClient;
        this.authClient = authClient;
    }

    /**
     * 根据输入类型选择远程 URL 单任务流程或本地文件批任务流程。
     */
    @Override
    public OcrResponse recognize(OcrRequest request) {
        OcrResponse error = validateRequest(request);
        if (error != null) return error;
        error = validateCredentials();
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
            // 预签名地址不接受业务 Bearer Token；Content-Type 必须与 OSS 签名中的文件类型一致。
            String mimeType = URLConnection.guessContentTypeFromName(request.getFileName());
            if (StringUtil.noText(mimeType)) mimeType = "application/octet-stream";
            Request upload = new Request.Builder().url(uploadUrl)
                .put(RequestBody.create(request.getFile(), MediaType.parse(mimeType))).build();
            boolean retryWithoutContentType;
            try (Response response = uploadClient.newCall(upload).execute()) {
                if (response.isSuccessful()) {
                    retryWithoutContentType = false;
                } else if (response.code() == 403) {
                    // MinerU 原生 API Token 和 OpenXLab JWT 签发的 OSS URL 对 Content-Type 要求不同。
                    retryWithoutContentType = true;
                } else {
                    // OSS 错误正文可能包含临时 AccessKey、签名和对象路径，不向业务响应传播。
                    return OcrResponse.error("HTTP " + response.code(), "MinerU file upload failed");
                }
            }
            if (retryWithoutContentType) {
                Request fallbackUpload = upload.newBuilder()
                    .put(RequestBody.create(request.getFile(), null)).build();
                try (Response response = uploadClient.newCall(fallbackUpload).execute()) {
                    if (!response.isSuccessful())
                        return OcrResponse.error("HTTP " + response.code(), "MinerU file upload failed");
                }
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
        OcrResponse credentialError = validateCredentials();
        if (credentialError != null) return credentialError;
        boolean batch = batchTaskIds.contains(taskId);
        OcrResponse response = batch ? getBatchResult(taskId)
            : resolveCompletedResult(parseResponse(httpClient.get(config.getQueryUrl(taskId), headers(false)), false));
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
        OcrResponse credentialError = validateCredentials();
        if (credentialError != null) return credentialError;
        OcrResponse response = parseResponse(httpClient.get(config.getBatchQueryUrl(batchId), headers(false)), false);
        if (response != null && StringUtil.noText(response.getTaskId())) response.setTaskId(batchId);
        return resolveCompletedResult(response);
    }

    private OcrResponse resolveCompletedResult(OcrResponse response) {
        resolveStructuredDocument(response);
        return resolveResultMarkdown(response);
    }

    private void resolveStructuredDocument(OcrResponse response) {
        if (response == null || response.isError() || response.getStatus() != OcrTaskStatus.SUCCEEDED) return;
        OcrResource archive = findResource(response, "archive");
        if (archive == null) return;
        try {
            byte[] bytes = httpClient.getBytes(archive.getUrl());
            String layout = readArchiveEntry(bytes, "layout.json");
            if (StringUtil.hasText(layout)) response.setDocument(parseLayoutDocument(layout));
        } catch (Exception error) {
            response.putMetadata("structured_document_error", error.getMessage());
        }
    }

    static OcrDocument parseLayoutDocument(String json) {
        JSONObject root = JSON.parseObject(json);
        OcrDocument document = new OcrDocument();
        JSONArray pages = root.getJSONArray("pdf_info");
        if (pages == null) return document;
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            JSONObject source = pages.getJSONObject(pageIndex);
            if (source == null) continue;
            OcrPage page = new OcrPage();
            page.setPageNumber(source.containsKey("page_idx") ? source.getIntValue("page_idx") : pageIndex);
            page.setCoordinateOrigin(OcrCoordinateOrigin.TOP_LEFT);
            JSONArray size = source.getJSONArray("page_size");
            if (size != null && size.size() >= 2)
                page.setSize(new OcrPageSize(size.getDoubleValue(0), size.getDoubleValue(1), OcrCoordinateUnit.UNKNOWN));
            JSONArray blocks = source.getJSONArray("para_blocks");
            if (blocks != null) for (int i = 0; i < blocks.size(); i++) {
                JSONObject block = blocks.getJSONObject(i);
                if (block != null) page.addBlock(parseLayoutBlock(block, i));
            }
            document.addPage(page);
        }
        return document;
    }

    private static OcrBlock parseLayoutBlock(JSONObject source, int fallbackIndex) {
        OcrBlock block = new OcrBlock();
        block.setIndex(source.containsKey("index") ? source.getIntValue("index") : fallbackIndex);
        String providerType = source.getString("type");
        block.setProviderType(providerType);
        block.setType(mapBlockType(providerType));
        block.setBoundingBox(parseBoundingBox(source.getJSONArray("bbox")));
        block.setTitleLevel(source.getInteger("level"));
        Object score = source.get("score");
        if (score != null) block.putMetadata("score", score);
        Content content = extractContent(source);
        block.setContent(content.text);
        block.setFormat(content.format);
        block.setImageUrl(content.imagePath);
        return block;
    }

    private static Content extractContent(JSONObject source) {
        StringBuilder text = new StringBuilder();
        String format = "text";
        String imagePath = null;
        JSONArray containers = source.getJSONArray("lines");
        if (containers == null) containers = source.getJSONArray("blocks");
        if (containers != null) for (int i = 0; i < containers.size(); i++) {
            JSONObject item = containers.getJSONObject(i);
            if (item == null) continue;
            if (item.getJSONArray("lines") != null || item.getJSONArray("blocks") != null) {
                Content nested = extractContent(item);
                if (StringUtil.hasText(nested.text)) appendText(text, nested.text);
                if (nested.imagePath != null) imagePath = nested.imagePath;
                if ("html".equals(nested.format)) format = "html";
                continue;
            }
            JSONArray spans = item.getJSONArray("spans");
            if (spans == null) continue;
            for (int j = 0; j < spans.size(); j++) {
                JSONObject span = spans.getJSONObject(j);
                if (span == null) continue;
                String html = span.getString("html");
                String value = StringUtil.hasText(html) ? html : span.getString("content");
                if (StringUtil.hasText(value)) appendText(text, value);
                if (StringUtil.hasText(html)) format = "html";
                if (StringUtil.hasText(span.getString("image_path"))) imagePath = span.getString("image_path");
            }
        }
        return new Content(text.toString(), format, imagePath);
    }

    private static void appendText(StringBuilder text, String value) {
        if (text.length() > 0) text.append('\n');
        text.append(value);
    }

    private static OcrBoundingBox parseBoundingBox(JSONArray bbox) {
        return bbox == null || bbox.size() < 4 ? null : new OcrBoundingBox(
            bbox.getDoubleValue(0), bbox.getDoubleValue(1), bbox.getDoubleValue(2), bbox.getDoubleValue(3));
    }

    private static OcrBlockType mapBlockType(String type) {
        if (type == null) return OcrBlockType.OTHER;
        String value = type.toLowerCase();
        if ("title".equals(value)) return OcrBlockType.TITLE;
        if ("image".equals(value) || "chart".equals(value)) return OcrBlockType.IMAGE;
        if ("table".equals(value)) return OcrBlockType.TABLE;
        if ("text".equals(value) || "abstract".equals(value) || "ref_text".equals(value)) return OcrBlockType.TEXT;
        return OcrBlockType.OTHER;
    }

    private static String readArchiveEntry(byte[] archive, String expectedName) throws IOException {
        if (archive == null || archive.length == 0) return null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 10_000) throw new IOException("OCR archive contains too many entries");
                if (entry.isDirectory() || !expectedName.equals(entry.getName())) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (output.size() + read > 20 * 1024 * 1024) throw new IOException("OCR layout JSON is too large");
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static OcrResource findResource(OcrResponse response, String type) {
        for (OcrResource resource : response.getResources()) {
            if (resource != null && type.equalsIgnoreCase(resource.getType()) && StringUtil.hasText(resource.getUrl()))
                return resource;
        }
        return null;
    }

    private static class Content {
        private final String text;
        private final String format;
        private final String imagePath;

        private Content(String text, String format, String imagePath) {
            this.text = text;
            this.format = format;
            this.imagePath = imagePath;
        }
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
        headers.put("Authorization", "Bearer " + bearerCredential());
        if (json) headers.put("Content-Type", "application/json");
        return headers;
    }

    private String bearerCredential() {
        if (StringUtil.hasText(config.getApiKey())) return config.getApiKey();
        return authClient.getJwt(config.getAccessKeyId(), config.getSecretAccessKey());
    }

    private OcrResponse validateCredentials() {
        if (StringUtil.hasText(config.getApiKey())) return null;
        boolean hasAccessKeyId = StringUtil.hasText(config.getAccessKeyId());
        boolean hasSecretAccessKey = StringUtil.hasText(config.getSecretAccessKey());
        if (!hasAccessKeyId || !hasSecretAccessKey) {
            return OcrResponse.error(hasAccessKeyId == hasSecretAccessKey
                ? "apiKey or accessKeyId/secretAccessKey must be configured"
                : "accessKeyId and secretAccessKey must be configured together");
        }
        try {
            bearerCredential();
            return null;
        } catch (RuntimeException e) {
            return OcrResponse.error("MinerU OpenXLab authentication failed: " + e.getMessage());
        }
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

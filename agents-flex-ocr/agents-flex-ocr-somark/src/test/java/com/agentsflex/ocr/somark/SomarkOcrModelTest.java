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
import com.agentsflex.core.model.ocr.OcrBlock;
import com.agentsflex.core.model.ocr.OcrBlockType;
import com.agentsflex.core.model.ocr.OcrCoordinateOrigin;
import com.agentsflex.core.model.ocr.OcrCoordinateUnit;
import com.agentsflex.core.model.ocr.OcrDocument;
import com.agentsflex.core.model.ocr.OcrPage;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * SoMark 端点、请求表单和响应映射单元测试。
 */
public class SomarkOcrModelTest {
    /**
     * 验证配置使用官方新版解析路径，而不是将停用的 extract 路径。
     */
    @Test
    public void shouldExposeOfficialApiDefaults() {
        SomarkOcrConfig config = new SomarkOcrConfig();

        assertEquals("https://somark.cn/api/v1/parse/async", config.getFullUrl());
        assertEquals("https://somark.cn/api/v1/parse/async_check", config.getQueryUrl("task-1"));
        assertEquals("https://somark.cn/api/v1/parse/sync", config.getSyncUrl());
        assertEquals(3_000L, config.getPollIntervalMillis());
    }

    /**
     * 验证 URL 输入、API Key、输出格式及对象配置按官方 multipart 协议提交。
     */
    @Test
    public void shouldSubmitAsyncUrlWithSerializedOptions() {
        AtomicReference<String> requestUrl = new AtomicReference<>();
        AtomicReference<Map<String, Object>> requestPayload = new AtomicReference<>();
        AgentsFlexHttpClient client = new AgentsFlexHttpClient() {
            @Override
            public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
                requestUrl.set(url);
                requestPayload.set(payload);
                return "{\"code\":0,\"message\":\"任务已提交\",\"data\":{" +
                    "\"task_id\":\"s-1\",\"status\":\"QUEUING\"}}";
            }
        };
        SomarkOcrModel model = new SomarkOcrModel(config(), client);
        OcrRequest request = OcrRequest.ofUrl("https://files.example.com/report.pdf");
        request.putOption("output_formats", Arrays.asList("markdown", "json"));
        request.putOption("element_formats", Collections.singletonMap("image", "url"));
        request.putOption("feature_config", Collections.singletonMap("enable_image_ocr", true));

        OcrResponse response = model.recognize(request);

        assertEquals("https://somark.cn/api/v1/parse/async", requestUrl.get());
        assertEquals("test-key", requestPayload.get().get("api_key"));
        assertEquals("https://files.example.com/report.pdf", requestPayload.get().get("file_url"));
        assertEquals(Arrays.asList("markdown", "json"), requestPayload.get().get("output_formats"));
        assertEquals("url", JSON.parseObject((String) requestPayload.get().get("element_formats")).getString("image"));
        assertTrue(JSON.parseObject((String) requestPayload.get().get("feature_config"))
            .getBooleanValue("enable_image_ocr"));
        assertEquals("s-1", response.getTaskId());
        assertEquals(OcrTaskStatus.QUEUED, response.getStatus());
    }

    /**
     * 验证同步接口直接返回 Markdown、结构化元数据和 ZIP 资源。
     */
    @Test
    public void shouldRecognizeSynchronously() throws Exception {
        File input = Files.createTempFile("somark-ocr-", ".pdf").toFile();
        Files.write(input.toPath(), "ocr".getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> requestUrl = new AtomicReference<>();
        try {
            AgentsFlexHttpClient client = new AgentsFlexHttpClient() {
                @Override
                public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
                    requestUrl.set(url);
                    assertEquals(input, payload.get("file"));
                    return successfulResult("sync-1", null, "# SoMark", "https://cdn.example/result.zip");
                }
            };
            SomarkOcrModel model = new SomarkOcrModel(config(), client);

            OcrResponse response = model.recognizeSync(OcrRequest.ofFile(input));

            assertEquals("https://somark.cn/api/v1/parse/sync", requestUrl.get());
            assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
            assertEquals("# SoMark", response.getMarkdown());
            assertEquals("archive", response.getResources().get(0).getType());
        } finally {
            Files.deleteIfExists(input.toPath());
        }
    }

    /**
     * 验证 SoMark JSON 页面、块和坐标信息完整映射到公共结构化文档。
     */
    @Test
    public void shouldMapStructuredDocumentLayout() {
        String json = "{\"code\":0,\"data\":{\"task_id\":\"layout-1\",\"status\":\"SUCCESS\"," +
            "\"result\":{\"file_name\":\"document.pdf\",\"outputs\":{\"json\":{\"pages\":[{" +
            "\"page_num\":0,\"page_size\":{\"h\":1684,\"w\":1190}," +
            "\"merge_content_from_pre_page\":false,\"blocks\":[{" +
            "\"idx\":0,\"type\":\"title\",\"bbox\":[72,50,540,80]," +
            "\"content\":\"第一章 引言\",\"format\":\"text\",\"captions\":[1]," +
            "\"img_url\":\"\",\"title_level\":1},{" +
            "\"idx\":1,\"type\":\"sidebar\",\"bbox\":[72,100,540,200]," +
            "\"content\":\"本文档介绍了...\",\"format\":\"text\",\"captions\":[]," +
            "\"img_url\":\"https://cdn.example/image.png\"," +
            "\"text_cross_page\":{\"page_num\":0,\"block_idx\":0}}]}]}}}}}";

        OcrResponse response = SomarkOcrModel.parseResponse(json, false);

        OcrDocument document = response.getDocument();
        assertNotNull(document);
        assertEquals("document.pdf", document.getFileName());
        assertEquals(1, document.getPages().size());
        OcrPage page = document.getPages().get(0);
        assertEquals(0, page.getPageNumber());
        assertEquals(1190D, page.getSize().getWidth(), 0D);
        assertEquals(1684D, page.getSize().getHeight(), 0D);
        assertEquals(OcrCoordinateUnit.UNKNOWN, page.getSize().getUnit());
        assertEquals(OcrCoordinateOrigin.UNKNOWN, page.getCoordinateOrigin());
        assertFalse(page.isMergedFromPreviousPage());
        assertEquals(2, page.getBlocks().size());

        OcrBlock title = page.getBlocks().get(0);
        assertEquals(OcrBlockType.TITLE, title.getType());
        assertEquals("title", title.getProviderType());
        assertEquals("第一章 引言", title.getContent());
        assertEquals(Integer.valueOf(1), title.getTitleLevel());
        assertEquals(Collections.singletonList(1), title.getCaptionBlockIndexes());
        assertEquals(72D, title.getBoundingBox().getX1(), 0D);
        assertEquals(80D, title.getBoundingBox().getY2(), 0D);

        OcrBlock unknown = page.getBlocks().get(1);
        assertEquals(OcrBlockType.OTHER, unknown.getType());
        assertEquals("sidebar", unknown.getProviderType());
        assertEquals("https://cdn.example/image.png", unknown.getImageUrl());
        assertNotNull(unknown.getMetadata("text_cross_page"));
    }

    /**
     * 验证查询使用 POST multipart，并物化成功结果中的内联 Markdown 图片。
     */
    @Test
    public void shouldQueryAndResolveSuccessfulResult() {
        AtomicReference<Map<String, Object>> requestPayload = new AtomicReference<>();
        AgentsFlexHttpClient client = new AgentsFlexHttpClient() {
            @Override
            public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
                assertEquals("https://somark.cn/api/v1/parse/async_check", url);
                requestPayload.set(payload);
                return successfulResult("async-1", "SUCCESS", "![](data:image/png;base64,AQID)", null);
            }
        };
        SomarkOcrModel model = new SomarkOcrModel(config(), client);
        model.setDocumentImagePublisher((bytes, mimeType, fileName) -> "https://cdn.example/image.png");

        OcrResponse response = model.getResult("async-1");

        assertEquals("async-1", requestPayload.get().get("task_id"));
        assertEquals("test-key", requestPayload.get().get("api_key"));
        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertEquals("![](https://cdn.example/image.png)", response.getMarkdown());
    }

    /**
     * 验证排队、处理中、失败和供应商错误码均转换为公共状态。
     */
    @Test
    public void shouldMapStatusesAndErrors() {
        assertEquals(OcrTaskStatus.QUEUED, SomarkOcrModel.parseResponse(
            "{\"code\":0,\"data\":{\"task_id\":\"q1\",\"status\":\"QUEUING\"}}", false).getStatus());
        assertEquals(OcrTaskStatus.RUNNING, SomarkOcrModel.parseResponse(
            "{\"code\":0,\"data\":{\"task_id\":\"q2\",\"status\":\"PROCESSING\"}}", false).getStatus());

        OcrResponse failed = SomarkOcrModel.parseResponse(
            "{\"code\":0,\"message\":\"查询成功\",\"data\":{\"task_id\":\"q3\"," +
                "\"status\":\"FAILED\",\"error\":{\"code\":\"1137\",\"message\":\"解析任务失败\"}}}", false);
        assertTrue(failed.isError());
        assertEquals("1137", failed.getErrorCode());
        assertEquals("解析任务失败", failed.getErrorMessage());

        OcrResponse rejected = SomarkOcrModel.parseResponse(
            "{\"code\":1107,\"message\":\"无效的 API Key\",\"data\":null}", true);
        assertTrue(rejected.isError());
        assertEquals("1107", rejected.getErrorCode());
    }

    /**
     * JSON-only 输出仍保持成功，不强制执行 Markdown 物化。
     */
    @Test
    public void shouldKeepJsonOnlyResultSuccessful() {
        AgentsFlexHttpClient client = new AgentsFlexHttpClient() {
            @Override
            public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
                return "{\"code\":0,\"data\":{\"task_id\":\"json-1\",\"status\":\"SUCCESS\"," +
                    "\"result\":{\"outputs\":{\"json\":{\"pages\":[]}}}}}";
            }
        };

        OcrResponse response = new SomarkOcrModel(config(), client).getResult("json-1");

        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertNull(response.getMarkdown());
        assertNotNull(response.getDocument());
        assertTrue(response.getDocument().getPages().isEmpty());
        JSONObject data = ((JSONObject) response.getMetadata("data"));
        assertNotNull(data.getJSONObject("result").getJSONObject("outputs").getJSONObject("json"));
    }

    /**
     * 核心字段应覆盖调用方 options，避免替换 API Key 或同时提交两种输入。
     */
    @Test
    public void shouldProtectCoreMultipartFields() {
        AtomicReference<Map<String, Object>> requestPayload = new AtomicReference<>();
        AgentsFlexHttpClient client = new AgentsFlexHttpClient() {
            @Override
            public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
                requestPayload.set(new LinkedHashMap<>(payload));
                return "{\"code\":0,\"data\":{\"task_id\":\"s-2\",\"status\":\"QUEUING\"}}";
            }
        };
        OcrRequest request = OcrRequest.ofUrl("https://files.example.com/actual.pdf");
        request.putOption("api_key", "overridden");
        request.putOption("file", "overridden");
        request.putOption("file_url", "overridden");

        new SomarkOcrModel(config(), client).recognize(request);

        assertEquals("test-key", requestPayload.get().get("api_key"));
        assertEquals("https://files.example.com/actual.pdf", requestPayload.get().get("file_url"));
        assertFalse(requestPayload.get().containsKey("file"));
    }

    private static SomarkOcrConfig config() {
        SomarkOcrConfig config = new SomarkOcrConfig();
        config.setApiKey("test-key");
        return config;
    }

    private static String successfulResult(String taskId, String status, String markdown, String zip) {
        JSONObject outputs = new JSONObject();
        if (markdown != null) outputs.put("markdown", markdown);
        outputs.put("json", Collections.singletonMap("pages", Collections.emptyList()));
        if (zip != null) outputs.put("zip", zip);
        JSONObject result = new JSONObject();
        result.put("file_name", "document.pdf");
        result.put("outputs", outputs);
        JSONObject data = new JSONObject();
        data.put("task_id", taskId);
        if (status != null) data.put("status", status);
        data.put("metadata", Collections.singletonMap("page_num", 1));
        data.put("result", result);
        JSONObject root = new JSONObject();
        root.put("code", 0);
        root.put("message", "任务成功");
        root.put("data", data);
        return root.toJSONString();
    }
}

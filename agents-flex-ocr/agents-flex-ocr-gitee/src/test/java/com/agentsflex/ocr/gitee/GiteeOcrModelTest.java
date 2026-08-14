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
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Gitee OCR 默认配置和响应兼容映射的单元测试。 */
public class GiteeOcrModelTest {
    /** URL 输入应由适配器下载为临时文件，上传结束后立即清理。 */
    @Test
    public void shouldDownloadUrlUploadMultipartAndDeleteTemporaryFile() {
        AtomicReference<File> uploaded = new AtomicReference<>();
        AtomicReference<String> downloaded = new AtomicReference<>();
        AgentsFlexHttpClient client = new AgentsFlexHttpClient() {
            @Override
            public byte[] getBytes(String url) {
                downloaded.set(url);
                return "ocr".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String multipartString(String url, Map<String, String> headers, Map<String, Object> params) {
                File file = (File) params.get("file");
                assertTrue(file.isFile());
                uploaded.set(file);
                return "{\"task_id\":\"g-url\"}";
            }
        };
        GiteeOcrConfig config = new GiteeOcrConfig();
        config.setApiKey("test-key");

        OcrResponse response = new GiteeOcrModel(config, client).recognize(
            OcrRequest.ofUrl("https://files.example.com/report.pdf?signature=test"));

        assertEquals("https://files.example.com/report.pdf?signature=test", downloaded.get());
        assertEquals(OcrTaskStatus.SUBMITTED, response.getStatus());
        assertEquals("g-url", response.getTaskId());
        assertNotNull(uploaded.get());
        assertFalse(uploaded.get().exists());
        assertTrue(uploaded.get().getName().endsWith(".pdf"));
    }
    /** 验证默认提交地址、任务查询地址和默认模型均符合供应商协议。 */
    @Test
    public void shouldExposeDocumentParsingDefaults() {
        GiteeOcrConfig config = new GiteeOcrConfig();
        assertEquals("https://ai.gitee.com/v1/async/documents/parse", config.getFullUrl());
        assertEquals("https://ai.gitee.com/v1/task/abc", config.getQueryUrl("abc"));
        assertEquals(GiteeOcrModels.UNLIMITED_OCR, config.getModel());
    }

    /** 验证成功响应中的内联 Markdown 和下载资源会被完整提取。 */
    @Test
    public void shouldParseSuccessfulOutput() {
        OcrResponse response = GiteeOcrModel.parseResponse("{\"task_id\":\"g1\",\"status\":\"success\",\"output\":{" +
            "\"markdown\":\"# Title\",\"markdown_url\":\"https://cdn/result.md\",\"json_url\":\"https://cdn/result.json\"}}", false);
        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertEquals("# Title", response.getMarkdown());
        assertEquals(2, response.getResources().size());
    }

    /** 验证真实查询响应中的顶层空字段不会破坏线程安全元数据容器。 */
    @Test
    public void shouldIgnoreNullProviderMetadata() {
        OcrResponse response = GiteeOcrModel.parseResponse(
            "{\"task_id\":\"g2\",\"status\":\"processing\",\"output\":null,\"error\":null}", false);

        assertEquals(OcrTaskStatus.RUNNING, response.getStatus());
        assertEquals("g2", response.getMetadata("task_id"));
        assertFalse(response.containsMetadata("output"));
        assertFalse(response.containsMetadata("error"));
    }

    /** 验证真实接口使用的 segments 数组会按返回顺序合并为 Markdown。 */
    @Test
    public void shouldParseSegmentContentFromRealApiShape() {
        OcrResponse response = GiteeOcrModel.parseResponse(
            "{\"task_id\":\"g3\",\"status\":\"success\",\"output\":{\"segments\":[" +
                "{\"index\":0,\"content\":\"# AgentsFlex\"}," +
                "{\"index\":1,\"content\":\"OCR result\"}]}}", false);

        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertEquals("# AgentsFlex\nOCR result", response.getMarkdown());
    }

    /** 验证 getResult 会物化 Markdown，并应用 model 上配置的图片处理器。 */
    @Test
    public void shouldResolveMarkdownWhenGettingSuccessfulResult() {
        AgentsFlexHttpClient client = new AgentsFlexHttpClient() {
            @Override
            public String get(String url, Map<String, String> headers) {
                return "{\"task_id\":\"g4\",\"status\":\"success\",\"output\":{" +
                    "\"markdown\":\"![](data:image/png;base64,AQID)\"}}";
            }
        };
        GiteeOcrModel model = new GiteeOcrModel(new GiteeOcrConfig(), client);
        model.setExtractedImageHandler((bytes, mimeType, fileName) -> "https://cdn.example/image.png");

        OcrResponse response = model.getResult("g4");

        assertEquals("![](https://cdn.example/image.png)", response.getMarkdown());
    }
}

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
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** MinerU 配置、普通任务及批任务响应解析的单元测试。 */
public class MineruOcrModelTest {
    /** 验证单任务、上传任务和批任务端点使用精确的官方默认路径。 */
    @Test
    public void shouldExposePreciseApiDefaults() {
        MineruOcrConfig config = new MineruOcrConfig();
        assertEquals("https://mineru.net/api/v4/extract/task", config.getFullUrl());
        assertEquals("https://mineru.net/api/v4/extract/task/abc", config.getQueryUrl("abc"));
        assertEquals("https://mineru.net/api/v4/extract-results/batch/abc", config.getBatchQueryUrl("abc"));
    }

    /** 验证完成任务会映射为成功，并提取完整结果压缩包资源。 */
    @Test
    public void shouldParseCompletedTask() {
        OcrResponse response = MineruOcrModel.parseResponse("{\"code\":0,\"data\":{" +
            "\"task_id\":\"m1\",\"state\":\"done\",\"full_zip_url\":\"https://cdn/full.zip\"}}", false);
        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertEquals("m1", response.getTaskId());
        assertEquals("archive", response.getResources().get(0).getType());
    }

    /** 验证 MinerU 数值错误码会以字符串形式保留在统一响应中。 */
    @Test
    public void shouldParseFailedTask() {
        OcrResponse response = MineruOcrModel.parseResponse("{\"code\":0,\"data\":{" +
            "\"task_id\":\"m2\",\"state\":\"failed\",\"err_code\":-30003,\"err_msg\":\"too many pages\"}}", false);
        assertTrue(response.isError());
        assertEquals("-30003", response.getErrorCode());
        assertEquals(OcrTaskStatus.FAILED, response.getStatus());
    }

    /** 验证进入嵌套 extract_result 后仍保留外层 batch_id，支持继续查询。 */
    @Test
    public void shouldKeepBatchIdWhenParsingBatchResult() {
        OcrResponse response = MineruOcrModel.parseResponse("{\"code\":0,\"data\":{" +
            "\"batch_id\":\"b1\",\"extract_result\":{\"state\":\"running\"}}}", false);
        assertEquals("b1", response.getTaskId());
        assertEquals(OcrTaskStatus.RUNNING, response.getStatus());
    }

    /**
     * 验证向预签名 URL 上传时不发送 Content-Type，防止该请求头参与 OSS 签名后造成 403。
     */
    @Test
    public void shouldUploadPresignedFileWithoutContentType() throws Exception {
        File input = Files.createTempFile("mineru-upload-", ".txt").toFile();
        Files.write(input.toPath(), "ocr".getBytes(StandardCharsets.UTF_8));
        AtomicReference<Request> uploadedRequest = new AtomicReference<>();
        try {
            AgentsFlexHttpClient apiClient = new AgentsFlexHttpClient() {
                @Override
                public String post(String url, Map<String, String> headers, String payload) {
                    return "{\"code\":0,\"data\":{\"batch_id\":\"batch-1\"," +
                        "\"file_urls\":[\"https://upload.example/input.txt\"]}}";
                }
            };
            OkHttpClient uploadClient = new OkHttpClient.Builder().addInterceptor(chain -> {
                uploadedRequest.set(chain.request());
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(new byte[0], null))
                    .build();
            }).build();

            MineruOcrModel model = new MineruOcrModel(new MineruOcrConfig(), apiClient, uploadClient);
            OcrResponse response = model.recognize(OcrRequest.ofFile(input));

            assertEquals(OcrTaskStatus.SUBMITTED, response.getStatus());
            assertNotNull(uploadedRequest.get());
            assertNull(uploadedRequest.get().header("Content-Type"));
        } finally {
            Files.deleteIfExists(input.toPath());
        }
    }

    /** 验证普通任务 getResult 会物化 Markdown，并应用图片处理器。 */
    @Test
    public void shouldResolveMarkdownWhenGettingSuccessfulResult() {
        AgentsFlexHttpClient apiClient = new AgentsFlexHttpClient() {
            @Override
            public String get(String url, Map<String, String> headers) {
                return "{\"code\":0,\"data\":{\"task_id\":\"m3\",\"state\":\"done\"," +
                    "\"markdown\":\"![](data:image/png;base64,AQID)\"}}";
            }
        };
        MineruOcrModel model = new MineruOcrModel(
            new MineruOcrConfig(), apiClient, new OkHttpClient());
        model.setExtractedImageHandler((bytes, mimeType, fileName) -> "https://cdn.example/image.png");

        OcrResponse response = model.getResult("m3");

        assertEquals("![](https://cdn.example/image.png)", response.getMarkdown());
    }
}

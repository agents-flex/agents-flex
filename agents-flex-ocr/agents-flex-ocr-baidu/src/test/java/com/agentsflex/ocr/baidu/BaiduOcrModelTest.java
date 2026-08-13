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

import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 百度 OCR 配置和响应映射的单元测试，不发送真实网络请求。 */
public class BaiduOcrModelTest {
    /** 验证官方端点、默认查询间隔，以及含空格 token 的 URL 编码。 */
    @Test
    public void shouldExposePaddleOcrDefaults() {
        BaiduOcrConfig config = new BaiduOcrConfig();
        config.setApiKey("token with space");
        assertEquals("https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task?access_token=token+with+space",
            config.getFullUrl());
        assertEquals("https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task/query?access_token=token+with+space",
            config.getQueryUrl());
        assertEquals(5_000L, config.getPollIntervalMillis());
    }

    /** 验证 bce-v3 API Key 不进入 URL，而是作为 Bearer 请求头发送。 */
    @Test
    public void shouldUseBearerHeaderForBceV3ApiKey() {
        BaiduOcrConfig config = new BaiduOcrConfig();
        config.setApiKey("bce-v3/test-key");
        AtomicReference<Request> capturedRequest = new AtomicReference<>();
        OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(chain -> {
            capturedRequest.set(chain.request());
            return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("{\"error_code\":0,\"result\":{\"task_id\":\"b1\"}}", null))
                .build();
        }).build();

        OcrResponse response = new BaiduOcrModel(config, httpClient).getResult("b1");

        assertFalse(response.isError());
        assertNotNull(capturedRequest.get());
        assertNull(capturedRequest.get().url().queryParameter("access_token"));
        assertEquals("Bearer bce-v3/test-key", capturedRequest.get().header("Authorization"));
    }

    /** 验证成功任务会映射状态，并按稳定顺序收集 Markdown 与 JSON 资源。 */
    @Test
    public void shouldParseSuccessfulResult() {
        OcrResponse response = BaiduOcrModel.parseResponse("{\"error_code\":0,\"result\":{" +
            "\"task_id\":\"b1\",\"status\":\"success\",\"markdown_url\":\"https://cdn/full.md\"," +
            "\"parse_result_url\":\"https://cdn/result.json\"}}", false);
        assertEquals(OcrTaskStatus.SUCCEEDED, response.getStatus());
        assertEquals("b1", response.getTaskId());
        assertEquals("markdown", response.getResources().get(0).getType());
        assertEquals("json", response.getResources().get(1).getType());
    }

    /** 验证任务级失败会同时设置统一失败状态和错误信息。 */
    @Test
    public void shouldParseFailedTask() {
        OcrResponse response = BaiduOcrModel.parseResponse("{\"error_code\":0,\"result\":{" +
            "\"task_id\":\"b2\",\"status\":\"failed\",\"task_error\":\"quota exhausted\"}}", false);
        assertTrue(response.isError());
        assertEquals(OcrTaskStatus.FAILED, response.getStatus());
        assertEquals("quota exhausted", response.getErrorMessage());
    }

    /** 验证顶层供应商错误码不会被误当作正常任务结果。 */
    @Test
    public void shouldParseProviderError() {
        OcrResponse response = BaiduOcrModel.parseResponse(
            "{\"error_code\":282003,\"error_msg\":\"missing parameters\",\"result\":null}", true);
        assertTrue(response.isError());
        assertEquals("282003", response.getErrorCode());
    }
}

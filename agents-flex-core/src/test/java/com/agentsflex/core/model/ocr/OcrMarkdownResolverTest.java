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
package com.agentsflex.core.model.ocr;

import okhttp3.*;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;

public class OcrMarkdownResolverTest {

    @Test
    public void shouldReturnInlineMarkdown() {
        OcrResponse response = successfulResponse();
        response.setMarkdown("# Title");

        assertEquals("# Title", OcrMarkdownResolver.getDefault().resolve(response));
    }

    @Test
    public void shouldPreserveRemoteImageAsAbsoluteUrlWhenHandlerIsConfigured() {
        OkHttpClient client = clientReturning(requestUrl ->
            "# Report\n\n![chart](images/chart.png)".getBytes(StandardCharsets.UTF_8));
        OcrResponse response = successfulResponse();
        response.addResource("markdown", "https://result.example/result.md");
        AtomicReference<Boolean> handled = new AtomicReference<>(false);

        String markdown = new OcrMarkdownResolver(client).resolve(response, (bytes, mimeType, name) -> {
            handled.set(true);
            return "https://cdn.example/image.png";
        });

        assertEquals(Boolean.FALSE, handled.get());
        assertEquals("# Report\n\n![chart](https://result.example/images/chart.png)", markdown);
    }

    @Test
    public void shouldMakeRelativeRemoteImageUrlAbsoluteWithoutHandler() {
        OkHttpClient client = clientReturning(requestUrl ->
            "![](images/chart.png)".getBytes(StandardCharsets.UTF_8));
        OcrResponse response = successfulResponse();
        response.addResource("markdown", "https://result.example/tasks/result.md");

        assertEquals("![](https://result.example/tasks/images/chart.png)",
            new OcrMarkdownResolver(client).resolve(response));
    }

    @Test
    public void shouldResolveArchiveAndReplaceRelativeImage() throws Exception {
        byte[] archive = zip(
            "document/full.md", "# Report\n\n![](images/page-1.png)".getBytes(StandardCharsets.UTF_8),
            "document/images/page-1.png", new byte[]{4, 5, 6});
        OcrResponse response = successfulResponse();
        response.addResource("archive", "https://result.example/result.zip");

        String markdown = new OcrMarkdownResolver(clientReturning(url -> archive)).resolve(response,
            (bytes, mimeType, name) -> "https://cdn.example/" + name);

        assertEquals("# Report\n\n![](https://cdn.example/page-1.png)", markdown);
    }

    @Test
    public void shouldResolveMarkdownDuringRecognizeAndWait() {
        BaseOcrConfig config = new BaseOcrConfig();
        config.setTimeoutMillis(100);
        config.setPollIntervalMillis(1);
        BaseOcrModel<BaseOcrConfig> model = new BaseOcrModel<BaseOcrConfig>(config) {
            public OcrResponse recognize(OcrRequest request) {
                OcrResponse response = new OcrResponse();
                response.setTaskId("task-1");
                response.setStatus(OcrTaskStatus.SUBMITTED);
                return response;
            }

            public OcrResponse getResult(String taskId) {
                OcrResponse response = successfulResponse();
                response.setMarkdown("![](data:image/png;base64,AQID)");
                return resolveResultMarkdown(response);
            }
        };
        model.setExtractedImageHandler((bytes, mimeType, name) -> "https://cdn.example/image.png");

        OcrResponse response = model.recognizeAndWait(OcrRequest.ofUrl("https://example.com/input.pdf"));

        assertEquals("![](https://cdn.example/image.png)", response.getMarkdown());
    }

    @Test
    public void shouldUsePlainTextAsMarkdownFallback() {
        OcrResponse response = successfulResponse();
        response.setText("plain OCR text");

        assertEquals("plain OCR text", OcrMarkdownResolver.getDefault().resolve(response));
    }

    private static OcrResponse successfulResponse() {
        OcrResponse response = new OcrResponse();
        response.setStatus(OcrTaskStatus.SUCCEEDED);
        return response;
    }

    private static OkHttpClient clientReturning(BytesProvider provider) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            byte[] bytes = provider.get(chain.request().url().toString());
            return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(bytes, MediaType.parse("application/octet-stream")))
                .build();
        }).build();
    }

    private static byte[] zip(String firstName, byte[] firstBytes, String secondName, byte[] secondBytes)
        throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(firstName));
            zip.write(firstBytes);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(secondName));
            zip.write(secondBytes);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private interface BytesProvider {
        byte[] get(String url);
    }
}

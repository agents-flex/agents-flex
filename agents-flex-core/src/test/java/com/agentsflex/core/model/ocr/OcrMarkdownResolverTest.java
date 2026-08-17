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
    public void shouldDownloadRemoteImageWhenHandlerIsConfigured() {
        OkHttpClient client = clientReturning(requestUrl -> requestUrl.endsWith("result.md")
            ? "# Report\n\n![chart](images/chart.png)".getBytes(StandardCharsets.UTF_8)
            : new byte[]{1, 2, 3});
        OcrResponse response = successfulResponse();
        response.addResource("markdown", "https://result.example/result.md");
        AtomicReference<String> handledName = new AtomicReference<>();

        String markdown = new OcrMarkdownResolver(client).resolve(response, (bytes, mimeType, name) -> {
            assertEquals(3, bytes.length);
            assertEquals("image/png", mimeType);
            handledName.set(name);
            return "https://cdn.example/image.png";
        });

        assertEquals("chart.png", handledName.get());
        assertEquals("# Report\n\n![chart](https://cdn.example/image.png)", markdown);
    }

    @Test
    public void shouldDownloadAbsoluteSignedImageUrlWithoutChangingItsQuery() {
        String signedUrl = "https://result.example/images/chart.jpg?authorization=signed-value";
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        OkHttpClient client = clientReturning(requestUrl -> {
            requestedUrl.set(requestUrl);
            return new byte[]{1, 2, 3};
        });
        OcrResponse response = successfulResponse();
        response.setMarkdown("![](" + signedUrl + ")");

        String markdown = new OcrMarkdownResolver(client).resolve(response,
            (bytes, mimeType, name) -> "https://cdn.example/chart.jpg");

        assertEquals(signedUrl, requestedUrl.get());
        assertEquals("![](https://cdn.example/chart.jpg)", markdown);
    }

    @Test
    public void shouldRewriteHtmlImageReturnedByBaidu() {
        String signedUrl = "https://result.example/images/chart.jpg?authorization=signed-value";
        OkHttpClient client = clientReturning(requestUrl -> new byte[]{1, 2, 3});
        OcrResponse response = successfulResponse();
        response.setMarkdown("<div><img src=\"" + signedUrl + "\" alt=\"Image\" width=\"39%\" /></div>");

        String markdown = new OcrMarkdownResolver(client).resolve(response,
            (bytes, mimeType, name) -> "https://cdn.example/chart.jpg");

        assertEquals("<div><img src=\"https://cdn.example/chart.jpg\" alt=\"Image\" width=\"39%\" /></div>",
            markdown);
    }

    @Test
    public void shouldRemoveEntireHtmlImageWhenHandlerReturnsEmptyUrl() {
        OcrResponse response = successfulResponse();
        response.setMarkdown("before<img src=\"data:image/png;base64,AQID\" alt=\"Image\" />after");

        String markdown = OcrMarkdownResolver.getDefault().resolve(response,
            (bytes, mimeType, name) -> null);

        assertEquals("beforeafter", markdown);
    }

    @Test
    public void shouldDetectImageMimeTypeFromBytesBeforeFileExtension() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        OkHttpClient client = clientReturning(requestUrl -> png);
        OcrResponse response = successfulResponse();
        response.setMarkdown("![](https://result.example/image.jpg)");
        AtomicReference<String> handledMimeType = new AtomicReference<>();

        new OcrMarkdownResolver(client).resolve(response, (bytes, mimeType, name) -> {
            handledMimeType.set(mimeType);
            return "https://cdn.example/image.png";
        });

        assertEquals("image/png", handledMimeType.get());
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
        model.setDocumentImagePublisher((bytes, mimeType, name) -> "https://cdn.example/image.png");

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

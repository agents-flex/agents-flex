package com.agentsflex.image.gitee;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class GiteeImageModelTest {

    @Test
    public void shouldGenerateImageWithJsonRequest() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeImageModel model = new GiteeImageModel(config(), http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel(GiteeImageModels.FLUX_1_DEV);
        request.setPrompt("A paper-cut landscape");
        request.setSize(1024, 768);
        request.setN(2);
        request.setResponseFormat("url");
        request.setUser("user-1");
        request.addOption("custom_parameter", 0.8F);

        ImageResponse response = model.generate(request);

        assertFalse(response.isError());
        assertEquals(2, response.getImages().size());
        assertEquals("https://ai.gitee.com/v1/images/generations", http.url);
        assertEquals("application/json", http.headers.get("Content-Type"));
        assertEquals("Bearer test-key", http.headers.get("Authorization"));
        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals(GiteeImageModels.FLUX_1_DEV, payload.getString("model"));
        assertEquals("1024x768", payload.getString("size"));
        assertEquals(2, payload.getIntValue("n"));
        assertEquals("user-1", payload.getString("user"));
        assertEquals(0.8F, payload.getFloatValue("custom_parameter"), 0.001F);
    }

    @Test
    public void shouldEditImageWithMultipartRequest() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeImageModel model = new GiteeImageModel(config(), http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel(GiteeImageModels.QWEN_IMAGE_EDIT);
        request.setPrompt("Replace the sky");
        request.setN(1);
        request.setResponseFormat("b64_json");
        request.addInputImage(Image.ofUrl("https://example.com/source.png"));
        request.addOption(GiteeImageModel.OPTION_MASK, Image.ofBytes(new byte[]{1, 2, 3}, "image/png"));
        request.addOption(GiteeImageModel.OPTION_TASK_TYPES, Collections.singletonList("style"));

        ImageResponse response = model.generate(request);

        assertFalse(response.isError());
        assertEquals("https://ai.gitee.com/v1/images/edits", http.url);
        assertNull(http.headers.get("Content-Type"));
        assertEquals("https://example.com/source.png", http.multipart.get("image"));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) http.multipart.get("mask"));
        assertEquals("style", http.multipart.get("task_types"));
        assertEquals(GiteeImageModels.QWEN_IMAGE_EDIT, http.multipart.get("model"));
        assertEquals("b64_json", http.multipart.get("response_format"));
    }

    @Test
    public void shouldUploadImageBytesForEditing() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeImageModel model = new GiteeImageModel(config(), http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.addInputImage(Image.ofBytes(new byte[]{4, 5, 6}, "image/jpeg"));

        model.generate(request);

        assertArrayEquals(new byte[]{4, 5, 6}, (byte[]) http.multipart.get("image"));
    }

    @Test
    public void shouldParseBase64Response() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        String b64 = java.util.Base64.getEncoder().encodeToString("image".getBytes(StandardCharsets.UTF_8));
        http.response = "{\"created\":1,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A geometric poster");

        ImageResponse response = new GiteeImageModel(config(), http).generate(request);

        assertFalse(response.isError());
        assertEquals(b64, response.getImage().getB64Json());
        assertEquals(1, response.getMetadata("created"));
    }

    @Test
    public void shouldParseErrorResponse() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        http.response = "{\"error\":{\"code\":\"InvalidParameter\",\"message\":\"invalid size\",\"type\":\"invalid_request_error\"}}";

        ImageResponse response = new GiteeImageModel(config(), http).generate(new GenerateImageRequest());

        assertTrue(response.isError());
        assertEquals("InvalidParameter", response.getErrorCode());
        assertEquals("invalid size", response.getErrorMessage());
    }

    @Test
    public void shouldRejectInvalidRequestsBeforeHttpCall() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeImageModel model = new GiteeImageModel(config(), http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setN(2);
        request.setInputImages(Arrays.asList(
            Image.ofUrl("https://example.com/one.png"),
            Image.ofUrl("https://example.com/two.png")
        ));

        ImageResponse response = model.generate(request);

        assertTrue(response.isError());
        assertNull(http.url);
    }

    @Test
    public void shouldPreferRequestModelOverConfigModel() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel(GiteeImageModels.KOLORS);

        new GiteeImageModel(config(), http).generate(request);

        assertEquals(GiteeImageModels.KOLORS, JSON.parseObject(http.payload).getString("model"));
    }

    private GiteeImageModelConfig config() {
        GiteeImageModelConfig config = new GiteeImageModelConfig();
        config.setApiKey("test-key");
        return config;
    }

    private static class StubAgentsFlexHttpClient extends AgentsFlexHttpClient {
        String url;
        String payload;
        Map<String, Object> multipart;
        Map<String, String> headers;
        String response = "{\"created\":1,\"data\":[" +
            "{\"url\":\"https://example.com/one.png\"}," +
            "{\"url\":\"https://example.com/two.png\"}]}";

        @Override
        public String post(String url, Map<String, String> headers, String payload) {
            this.url = url;
            this.headers = headers;
            this.payload = payload;
            return response;
        }

        @Override
        public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
            this.url = url;
            this.headers = headers;
            this.multipart = payload;
            return response;
        }
    }
}

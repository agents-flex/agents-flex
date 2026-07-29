package com.agentsflex.image.openai;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpenAIImageModelTest {
    @Test
    public void shouldExposeOptionsForSelectedModel() {
        OpenAIImageModelConfig config = new OpenAIImageModelConfig();

        assertEquals(OpenAIImageModels.GPT_IMAGE_1_5, config.getModel());
        assertEquals(Arrays.asList("1:1", "3:2", "2:3"), config.getSupportedAspectRatios());
        assertEquals(Arrays.asList("auto", "low", "medium", "high"), config.getSupportedQualities());

        config.setModel(OpenAIImageModels.DALL_E_3);

        assertEquals(Arrays.asList("1024x1024", "1792x1024", "1024x1792"),
            config.getSupportedResolutions());
        assertEquals(Arrays.asList("standard", "hd"), config.getSupportedQualities());
    }

    @Test
    public void shouldGenerateGptImageAndParseBase64Response() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        String b64 = java.util.Base64.getEncoder().encodeToString("image".getBytes(StandardCharsets.UTF_8));
        http.response = "{\"created\":1,\"data\":[{\"b64_json\":\"" + b64 + "\"}]," +
            "\"usage\":{\"total_tokens\":100}}";
        OpenAIImageModelConfig config = config();
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A small cabin beside a mountain lake");
        request.setResolution("1536x1024");
        request.setQuality("high");
        request.setOutputFormat("webp");
        request.setN(2);
        request.setUser("user-1");
        request.addOption(OpenAIImageModel.OPTION_BACKGROUND, "opaque");
        request.addOption(OpenAIImageModel.OPTION_MODERATION, "auto");
        request.addOption(OpenAIImageModel.OPTION_OUTPUT_COMPRESSION, 80);

        ImageResponse response = new OpenAIImageModel(config, http).generate(request);

        assertFalse(response.isError());
        assertEquals(b64, response.getImage().getB64Json());
        assertEquals("image/webp", response.getImage().getMimeType());
        assertEquals(1, response.getMetadata("created"));
        assertEquals("https://api.openai.com/v1/images/generations", http.url);
        assertEquals("Bearer test-key", http.headers.get("Authorization"));
        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals(OpenAIImageModels.GPT_IMAGE_1_5, payload.getString("model"));
        assertEquals("1536x1024", payload.getString("size"));
        assertEquals("high", payload.getString("quality"));
        assertEquals("webp", payload.getString("output_format"));
        assertEquals(80, payload.getIntValue("output_compression"));
        assertNull(payload.get("stream"));
    }

    @Test
    public void shouldParseDallEUrlResponse() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        http.response = "{\"created\":1,\"data\":[{\"url\":\"https://example.com/image.png\"," +
            "\"revised_prompt\":\"revised\"}]}";
        OpenAIImageModelConfig config = config();
        config.setModel(OpenAIImageModels.DALL_E_3);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A linocut city poster");
        request.setN(1);
        request.setResponseFormat("url");
        request.setQuality("hd");
        request.setStyle("natural");

        ImageResponse response = new OpenAIImageModel(config, http).generate(request);

        assertFalse(response.isError());
        assertEquals("https://example.com/image.png", response.getImage().getUrl());
        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals("url", payload.getString("response_format"));
        assertEquals("natural", payload.getString("style"));
    }

    @Test
    public void shouldParseOpenAIErrorResponse() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        http.response = "{\"error\":{\"code\":\"invalid_value\",\"message\":\"Invalid size\"," +
            "\"type\":\"invalid_request_error\"}}";
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A test image");

        ImageResponse response = new OpenAIImageModel(config(), http).generate(request);

        assertTrue(response.isError());
        assertEquals("invalid_value", response.getErrorCode());
        assertEquals("Invalid size", response.getErrorMessage());
    }

    @Test
    public void shouldRejectUnsupportedSynchronousAndModelParameters() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("Edit this image");
        request.addInputImage(Image.ofUrl("https://example.com/input.png"));

        ImageResponse inputError = new OpenAIImageModel(config(), http).generate(request);

        assertTrue(inputError.isError());
        assertNull(http.url);

        request.setInputImages(null);
        request.setN(2);
        request.setModel(OpenAIImageModels.DALL_E_3);
        ImageResponse countError = new OpenAIImageModel(config(), http).generate(request);
        assertTrue(countError.isError());

        request.setN(1);
        request.setModel(OpenAIImageModels.GPT_IMAGE_1_5);
        request.setResponseFormat("url");
        ImageResponse formatError = new OpenAIImageModel(config(), http).generate(request);
        assertTrue(formatError.isError());
    }

    @Test
    public void shouldValidateValuesAgainstRequestedModelCapabilities() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A test image");
        request.setResolution("2048x2048");

        ImageResponse sizeError = new OpenAIImageModel(config(), http).generate(request);

        assertTrue(sizeError.isError());
        assertTrue(sizeError.getErrorMessage().contains("supported values"));
        assertNull(http.url);

        request.setResolution("1024x1024");
        request.setQuality("ultra");

        ImageResponse qualityError = new OpenAIImageModel(config(), http).generate(request);

        assertTrue(qualityError.isError());
        assertTrue(qualityError.getErrorMessage().contains("quality 'ultra'"));
        assertNull(http.url);

        request.setModel(OpenAIImageModels.DALL_E_3);
        request.setResolution("1792x1024");
        request.setQuality("hd");
        request.setN(1);

        ImageResponse response = new OpenAIImageModel(config(), http).generate(request);

        assertFalse(response.isError());
    }

    private OpenAIImageModelConfig config() {
        OpenAIImageModelConfig config = new OpenAIImageModelConfig();
        config.setApiKey("test-key");
        return config;
    }

    private static class StubAgentsFlexHttpClient extends AgentsFlexHttpClient {
        String url;
        String payload;
        String response = "{\"created\":1,\"data\":[{\"b64_json\":\"aW1hZ2U=\"}]}";
        Map<String, String> headers;

        @Override
        public String post(String url, Map<String, String> headers, String payload) {
            this.url = url;
            this.headers = headers;
            this.payload = payload;
            return response;
        }
    }
}

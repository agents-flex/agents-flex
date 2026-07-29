package com.agentsflex.image.gemini;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GeminiImageModelTest {
    @Test
    public void shouldExposeCapabilitiesForSelectedNanoBananaModel() {
        GeminiImageModelConfig config = new GeminiImageModelConfig();

        assertEquals(GeminiImageModels.GEMINI_3_1_FLASH_IMAGE, config.getModel());
        assertEquals(java.util.Arrays.asList("512", "1K", "2K", "4K"), config.getSupportedResolutions());
        assertTrue(config.getSupportedAspectRatios().contains("1:8"));
        assertEquals(Integer.valueOf(14), config.getMaxInputImages());

        config.setModel(GeminiImageModels.GEMINI_2_5_FLASH_IMAGE);

        assertEquals(Collections.singletonList("1K"), config.getSupportedResolutions());
        assertFalse(config.getSupportedAspectRatios().contains("1:8"));
        assertEquals(Integer.valueOf(3), config.getMaxInputImages());
        assertNull(config.getSupportedQualities());
    }

    @Test
    public void shouldGenerateImageWithRestPayload() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GeminiImageModelConfig config = config();
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("Create a clean botanical poster");
        request.setResolution("2K");
        request.addOption(GeminiImageModel.OPTION_ASPECT_RATIO, "16:9");
        request.addOption("tools", Collections.singletonList(
            Collections.singletonMap("google_search", Collections.emptyMap())
        ));

        ImageResponse response = new GeminiImageModel(config, http).generate(request);

        assertFalse(response.isError());
        assertEquals("generated-image", new String(
            java.util.Base64.getDecoder().decode(response.getImage().getB64Json()), StandardCharsets.UTF_8));
        assertEquals("image/png", response.getImage().getMimeType());
        assertEquals("https://generativelanguage.googleapis.com/v1/models/gemini-3.1-flash-image:generateContent",
            http.url);
        assertEquals("test-key", http.headers.get("x-goog-api-key"));

        JSONObject payload = JSON.parseObject(http.payload);
        JSONArray parts = payload.getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
        assertEquals("Create a clean botanical poster", parts.getJSONObject(0).getString("text"));
        JSONObject generationConfig = payload.getJSONObject("generationConfig");
        assertEquals("IMAGE", generationConfig.getJSONArray("responseModalities").getString(0));
        JSONObject image = generationConfig.getJSONObject("imageConfig");
        assertEquals("16:9", image.getString("aspectRatio"));
        assertEquals("2K", image.getString("imageSize"));
        assertEquals(1, payload.getJSONArray("tools").size());
    }

    @Test
    public void shouldValidateValuesAgainstRequestedModelCapabilities() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A test image");
        request.setModel(GeminiImageModels.GEMINI_2_5_FLASH_IMAGE);
        request.setResolution("2K");

        ImageResponse resolutionError = new GeminiImageModel(config(), http).generate(request);

        assertTrue(resolutionError.isError());
        assertTrue(resolutionError.getErrorMessage().contains("supported values: [1K]"));
        assertNull(http.url);

        request.setResolution("1K");
        request.addOption(GeminiImageModel.OPTION_ASPECT_RATIO, "1:8");

        ImageResponse ratioError = new GeminiImageModel(config(), http).generate(request);

        assertTrue(ratioError.isError());
        assertNull(http.url);
    }

    @Test
    public void shouldAddByteAndUrlReferenceImages() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("Combine these references");
        request.addInputImage(Image.ofBytes("first".getBytes(StandardCharsets.UTF_8), "image/webp"));
        request.addInputImage(Image.ofUrl("https://example.com/second.jpg"));

        ImageResponse response = new GeminiImageModel(config(), http).generate(request);

        assertFalse(response.isError());
        JSONObject payload = JSON.parseObject(http.payload);
        JSONArray parts = payload.getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
        JSONObject first = parts.getJSONObject(1).getJSONObject("inlineData");
        JSONObject second = parts.getJSONObject(2).getJSONObject("inlineData");
        assertEquals("image/webp", first.getString("mimeType"));
        assertEquals(java.util.Base64.getEncoder().encodeToString(http.downloadBytes), second.getString("data"));
        assertEquals("image/jpeg", second.getString("mimeType"));
        assertEquals("https://example.com/second.jpg", http.downloadUrl);
    }

    @Test
    public void shouldMergeGenerationConfigOptions() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A diagram");
        request.addOption(GeminiImageModel.OPTION_GENERATION_CONFIG,
            Collections.singletonMap("temperature", 0.7F));

        new GeminiImageModel(config(), http).generate(request);

        JSONObject generationConfig = JSON.parseObject(http.payload).getJSONObject("generationConfig");
        assertEquals(0.7F, generationConfig.getFloatValue("temperature"), 0.001F);
        assertEquals("IMAGE", generationConfig.getJSONArray("responseModalities").getString(0));
    }

    @Test
    public void shouldParseGoogleErrorAndRejectModelLimits() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        http.response = "{\"error\":{\"code\":400,\"message\":\"Invalid argument\"," +
            "\"status\":\"INVALID_ARGUMENT\"}}";
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("A test image");

        ImageResponse error = new GeminiImageModel(config(), http).generate(request);

        assertTrue(error.isError());
        assertEquals("INVALID_ARGUMENT", error.getErrorCode());
        assertEquals("Invalid argument", error.getErrorMessage());

        http.url = null;
        request.setModel(GeminiImageModels.GEMINI_2_5_FLASH_IMAGE);
        request.setInputImages(java.util.Arrays.asList(
            Image.ofBytes(new byte[]{1}, "image/png"),
            Image.ofBytes(new byte[]{2}, "image/png"),
            Image.ofBytes(new byte[]{3}, "image/png"),
            Image.ofBytes(new byte[]{4}, "image/png")
        ));
        ImageResponse limitError = new GeminiImageModel(config(), http).generate(request);
        assertTrue(limitError.isError());
        assertNull(http.url);
    }

    private GeminiImageModelConfig config() {
        GeminiImageModelConfig config = new GeminiImageModelConfig();
        config.setApiKey("test-key");
        return config;
    }

    private static class StubAgentsFlexHttpClient extends AgentsFlexHttpClient {
        String url;
        String payload;
        String downloadUrl;
        Map<String, String> headers;
        byte[] downloadBytes = "downloaded".getBytes(StandardCharsets.UTF_8);
        String response = "{\"candidates\":[{\"content\":{\"parts\":[" +
            "{\"text\":\"done\"},{\"inlineData\":{\"mimeType\":\"image/png\"," +
            "\"data\":\"Z2VuZXJhdGVkLWltYWdl\"}}]}}],\"usageMetadata\":{\"totalTokenCount\":10}}";

        @Override
        public String post(String url, Map<String, String> headers, String payload) {
            this.url = url;
            this.headers = headers;
            this.payload = payload;
            return response;
        }

        @Override
        public byte[] getBytes(String url) {
            this.downloadUrl = url;
            return downloadBytes;
        }
    }
}

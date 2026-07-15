package com.agentsflex.image.volcengine;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class VolcengineImageModelTest {

    @Test
    public void shouldBuildSeedreamMultiImageRequest() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        VolcengineImageModelConfig config = new VolcengineImageModelConfig();
        config.setApiKey("test-key");
        VolcengineImageModel model = new VolcengineImageModel(config, http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setPrompt("Create a coherent product campaign");
        request.addInputImage(Image.ofUrl("https://example.com/a.png"));
        request.addInputImage(Image.ofUrl("https://example.com/b.png"));
        request.setResolution("2K");
        request.setResponseFormat("url");
        request.setOutputFormat("png");
        request.setWatermark(false);
        request.setSequentialGeneration(true);
        request.setMaxImages(4);

        ImageResponse response = model.generate(request);

        assertEquals("https://example.com/generated.png", response.getImage().getUrl());
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/images/generations", http.url);
        assertEquals("Bearer test-key", http.headers.get("Authorization"));
        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals(VolcengineImageModels.SEEDREAM_5_0_LITE, payload.getString("model"));
        JSONArray images = payload.getJSONArray("image");
        assertEquals(2, images.size());
        assertEquals("auto", payload.getString("sequential_image_generation"));
        assertEquals(4, payload.getJSONObject("sequential_image_generation_options").getIntValue("max_images"));
    }

    private static class StubAgentsFlexHttpClient extends AgentsFlexHttpClient {
        String url;
        String payload;
        Map<String, String> headers = Collections.emptyMap();

        @Override
        public String post(String url, Map<String, String> headers, String payload) {
            this.url = url;
            this.headers = headers;
            this.payload = payload;
            return "{\"created\":1784080000,\"data\":[{\"url\":\"https://example.com/generated.png\"}]," +
                "\"usage\":{\"generated_images\":1}}";
        }
    }
}

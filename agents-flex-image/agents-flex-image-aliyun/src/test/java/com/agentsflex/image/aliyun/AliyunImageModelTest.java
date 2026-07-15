package com.agentsflex.image.aliyun;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageBoundingBox;
import com.agentsflex.core.model.image.ImageResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class AliyunImageModelTest {

    @Test
    public void shouldGenerateWan27FromMultipleImagesSynchronously() {
        StubHttpClient http = new StubHttpClient();
        AliyunImageModel model = new AliyunImageModel(config(), http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel(AliyunImageModels.WAN_2_7_IMAGE_PRO);
        request.setPrompt("Combine the subject and background");
        request.addInputImage(Image.ofUrl("https://example.com/subject.png"));
        request.addInputImage(Image.ofUrl("https://example.com/background.png"));
        request.setResolution("2K");
        request.setN(2);
        request.setWatermark(false);
        request.setSequentialGeneration(true);
        request.setBoundingBoxes(Arrays.asList(
            Collections.singletonList(ImageBoundingBox.of(10, 20, 300, 400)),
            Collections.<ImageBoundingBox>emptyList()
        ));

        ImageResponse response = model.generate(request);

        assertEquals(2, response.getImages().size());
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", http.url);
        assertFalse(http.headers.containsKey("X-DashScope-Async"));
        JSONObject payload = JSON.parseObject(http.payload);
        JSONArray content = payload.getJSONObject("input").getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        assertEquals("https://example.com/subject.png", content.getJSONObject(0).getString("image"));
        assertEquals("https://example.com/background.png", content.getJSONObject(1).getString("image"));
        assertEquals(request.getPrompt(), content.getJSONObject(2).getString("text"));
        JSONObject parameters = payload.getJSONObject("parameters");
        assertEquals("2K", parameters.getString("size"));
        assertEquals(2, parameters.getIntValue("n"));
        assertTrue(parameters.getBooleanValue("enable_sequential"));
        JSONArray bboxList = parameters.getJSONArray("bbox_list");
        assertEquals(2, bboxList.size());
        assertEquals(10, bboxList.getJSONArray(0).getJSONArray(0).getIntValue(0));
        assertTrue(bboxList.getJSONArray(1).isEmpty());
    }

    @Test
    public void shouldWaitForLegacyQwenTaskInsideGenerate() {
        StubHttpClient http = new StubHttpClient();
        AliyunImageModel model = new AliyunImageModel(config(), http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel(AliyunImageModels.QWEN_IMAGE_PLUS);
        request.setPrompt("A letterpress poster");
        request.setNegativePrompt("blurred text");
        request.setSize(1664, 928);
        request.setPromptExtend(true);

        ImageResponse result = model.generate(request);

        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis", http.postUrl);
        assertEquals("enable", http.postHeaders.get("X-DashScope-Async"));
        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals(request.getPrompt(), payload.getJSONObject("input").getString("prompt"));
        assertEquals("1664*928", payload.getJSONObject("parameters").getString("size"));
        assertEquals("https://example.com/qwen.png", result.getImage().getUrl());
        assertTrue(http.queryUrl.endsWith("/api/v1/tasks/task-qwen"));
    }

    @Test
    public void shouldCallQwenEditingSynchronously() {
        StubHttpClient http = new StubHttpClient();
        AliyunImageModel model = new AliyunImageModel(config(), http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.setModel(AliyunImageModels.QWEN_IMAGE_EDIT_PLUS);

        ImageResponse response = model.generate(request);

        assertFalse(response.isError());
        assertEquals(2, response.getImages().size());
        assertFalse(http.headers.containsKey("X-DashScope-Async"));
    }

    @Test
    public void shouldRejectBoundingBoxesThatDoNotAlignWithInputImages() {
        StubHttpClient http = new StubHttpClient();
        AliyunImageModel model = new AliyunImageModel(config(), http);
        GenerateImageRequest request = new GenerateImageRequest();
        request.addInputImage(Image.ofUrl("https://example.com/image.png"));
        request.setBoundingBoxes(Arrays.asList(
            Collections.singletonList(ImageBoundingBox.of(0, 0, 100, 100)),
            Collections.<ImageBoundingBox>emptyList()
        ));

        ImageResponse response = model.generate(request);

        assertTrue(response.isError());
        assertNull(http.url);
    }

    private AliyunImageModelConfig config() {
        AliyunImageModelConfig config = new AliyunImageModelConfig();
        config.setApiKey("test-key");
        config.setPollIntervalMillis(1L);
        config.setTimeoutMillis(1_000L);
        return config;
    }

    private static class StubHttpClient extends HttpClient {
        String url;
        String postUrl;
        String queryUrl;
        String payload;
        Map<String, String> headers = Collections.emptyMap();
        Map<String, String> postHeaders = Collections.emptyMap();

        @Override
        public String post(String url, Map<String, String> headers, String payload) {
            this.url = url;
            this.postUrl = url;
            this.headers = headers;
            this.postHeaders = headers;
            this.payload = payload;
            if (headers.containsKey("X-DashScope-Async")) {
                return "{\"output\":{\"task_id\":\"task-qwen\",\"task_status\":\"PENDING\"},\"request_id\":\"r1\"}";
            }
            return "{\"output\":{\"choices\":[{\"message\":{\"content\":[" +
                "{\"type\":\"image\",\"image\":\"https://example.com/one.png\"}," +
                "{\"type\":\"image\",\"image\":\"https://example.com/two.png\"}]}}]}," +
                "\"usage\":{\"image_count\":2},\"request_id\":\"r2\"}";
        }

        @Override
        public String get(String url, Map<String, String> headers) {
            this.url = url;
            this.queryUrl = url;
            this.headers = headers;
            return "{\"output\":{\"task_id\":\"task-qwen\",\"task_status\":\"SUCCEEDED\"," +
                "\"results\":[{\"url\":\"https://example.com/qwen.png\"}]}}";
        }
    }
}

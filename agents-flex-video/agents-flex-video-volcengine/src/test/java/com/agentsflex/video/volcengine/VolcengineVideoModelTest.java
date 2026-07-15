package com.agentsflex.video.volcengine;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class VolcengineVideoModelTest {
    @Test
    public void shouldSubmitAndQueryVideoTask() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        VolcengineVideoModelConfig config = new VolcengineVideoModelConfig();
        config.setApiKey("test-key");
        assertTrue(config.isSupportTextToVideo());
        assertTrue(config.isSupportImageToVideo());
        assertTrue(config.isSupportAudioGeneration());
        VolcengineVideoModel model = new VolcengineVideoModel(config, http);

        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setPrompt("A train crossing a snowy plain");
        request.setFirstFrame(Image.ofUrl("https://example.com/first.png"));
        request.setLastFrame(Image.ofUrl("https://example.com/last.png"));
        request.setDuration(5);
        request.setResolution("720p");
        request.setGenerateAudio(true);
        request.addOption("callback_url", "https://example.com/callback");

        VideoResponse submitted = model.generate(request);
        assertEquals("task-volc", submitted.getTaskId());
        assertEquals(VideoTaskStatus.QUEUED, submitted.getStatus());
        assertEquals("Bearer test-key", http.headers.get("Authorization"));

        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals(VolcengineVideoModels.SEEDANCE_2_0, payload.getString("model"));
        assertEquals(5, payload.getIntValue("duration"));
        assertTrue(payload.getBooleanValue("generate_audio"));
        assertEquals("https://example.com/callback", payload.getString("callback_url"));
        JSONArray content = payload.getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("first_frame", content.getJSONObject(1).getString("role"));
        assertEquals("last_frame", content.getJSONObject(2).getString("role"));

        VideoResponse result = model.getResult("task-volc");
        assertEquals(VideoTaskStatus.SUCCEEDED, result.getStatus());
        assertEquals("https://example.com/video.mp4", result.getVideo().getUrl());
        assertEquals("https://example.com/last.png", result.getVideo().getCoverUrl());
    }

    private static class StubAgentsFlexHttpClient extends AgentsFlexHttpClient {
        String payload;
        Map<String, String> headers;

        @Override
        public String post(String url, Map<String, String> headers, String payload) {
            this.headers = headers;
            this.payload = payload;
            return "{\"id\":\"task-volc\",\"status\":\"queued\"}";
        }

        @Override
        public String get(String url, Map<String, String> headers) {
            return "{\"id\":\"task-volc\",\"status\":\"succeeded\",\"content\":{" +
                "\"video_url\":\"https://example.com/video.mp4\"," +
                "\"last_frame_url\":\"https://example.com/last.png\"}}";
        }
    }
}

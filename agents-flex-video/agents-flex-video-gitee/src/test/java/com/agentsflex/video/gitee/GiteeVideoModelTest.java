package com.agentsflex.video.gitee;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.Video;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class GiteeVideoModelTest {

    @Test
    public void shouldSubmitTextVideoAndQueryResult() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeVideoModelConfig config = config();
        assertTrue(config.isSupportTextToVideo());
        assertTrue(config.isSupportImageToVideo());
        assertTrue(config.isSupportAudioInput());
        GiteeVideoModel model = new GiteeVideoModel(config, http);

        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setPrompt("A paper airplane flies over a city");
        request.addOption("duration", 5);

        VideoResponse submitted = model.generate(request);

        assertEquals("task-gitee", submitted.getTaskId());
        assertEquals(VideoTaskStatus.QUEUED, submitted.getStatus());
        assertEquals("https://ai.gitee.com/v1/async/videos/generations", http.url);
        assertEquals("Bearer test-key", http.headers.get("Authorization"));
        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals(GiteeVideoModels.HAPPYHORSE_1_1, payload.getString("model"));
        assertEquals(request.getPrompt(), payload.getString("prompt"));
        assertEquals(5, payload.getIntValue("duration"));

        VideoResponse result = model.getResult("task-gitee");
        assertEquals(VideoTaskStatus.SUCCEEDED, result.getStatus());
        assertEquals("https://example.com/generated.mp4", result.getVideo().getUrl());
        assertEquals(1280, result.getVideo().getWidth().intValue());
        assertEquals("https://ai.gitee.com/v1/task/task-gitee", http.url);
    }

    @Test
    public void shouldSubmitImageToVideoAsJson() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeVideoModel model = new GiteeVideoModel(config(), http);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(GiteeVideoModels.LTX_2);
        request.setPrompt("The camera slowly moves forward");
        request.setFirstFrame(Image.ofUrl("https://example.com/frame.png"));

        VideoResponse response = model.generate(request);

        assertFalse(response.isError());
        assertEquals("https://ai.gitee.com/v1/async/videos/image-to-video", http.url);
        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals(GiteeVideoModels.LTX_2, payload.getString("model"));
        assertEquals("https://example.com/frame.png", payload.getString("image_url"));
    }

    @Test
    public void shouldSubmitAudioVideoAsJson() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeVideoModel model = new GiteeVideoModel(config(), http);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(GiteeVideoModels.DUIX_HEYGEM);
        request.setAudioUrl("https://example.com/speech.wav");
        request.setSourceVideo(Video.ofUrl("https://example.com/person.mp4"));

        VideoResponse response = model.generate(request);

        assertFalse(response.isError());
        assertEquals("https://ai.gitee.com/v1/async/videos/audio-video-to-video", http.url);
        JSONObject payload = JSON.parseObject(http.payload);
        assertEquals("https://example.com/speech.wav", payload.getString("ref_audio"));
        assertEquals("https://example.com/person.mp4", payload.getString("ref_video"));
    }

    @Test
    public void shouldSubmitImageVideoAsMultipart() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeVideoModel model = new GiteeVideoModel(config(), http);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(GiteeVideoModels.HAPPYHORSE_1_0);
        request.setFirstFrame(Image.ofBytes(new byte[]{1, 2}, "image/png"));
        request.setSourceVideo(Video.ofBytes(new byte[]{3, 4, 5}, "video/mp4"));
        request.addOption("guidance", 1.5F);

        VideoResponse response = model.generate(request);

        assertFalse(response.isError());
        assertEquals("https://ai.gitee.com/v1/async/videos/image-video-to-video", http.url);
        assertEquals(GiteeVideoModels.HAPPYHORSE_1_0, http.multipart.get("model"));
        assertArrayEquals(new byte[]{1, 2}, (byte[]) http.multipart.get("ref_image"));
        assertArrayEquals(new byte[]{3, 4, 5}, (byte[]) http.multipart.get("drive_video"));
        assertEquals(1.5F, http.multipart.get("guidance"));
        assertFalse(http.headers.containsKey("Content-Type"));
    }

    @Test
    public void shouldRejectImageVideoUrlsBecauseEndpointRequiresFiles() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeVideoModel model = new GiteeVideoModel(config(), http);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setFirstFrame(Image.ofUrl("https://example.com/reference.png"));
        request.setSourceVideo(Video.ofUrl("https://example.com/drive.mp4"));

        VideoResponse response = model.generate(request);

        assertTrue(response.isError());
        assertEquals("InvalidParameter", response.getErrorCode());
        assertNull(http.url);
    }

    @Test
    public void shouldRejectReferenceImageWithoutSourceVideo() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        GiteeVideoModel model = new GiteeVideoModel(config(), http);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.addReferenceImage(Image.ofBytes(new byte[]{1}, "image/png"));

        VideoResponse response = model.generate(request);

        assertTrue(response.isError());
        assertEquals("InvalidParameter", response.getErrorCode());
        assertNull(http.url);
    }

    @Test
    public void shouldParseNestedVideoListWithoutDuplicates() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        http.queryResponse = "{\"task_id\":\"task-gitee\",\"status\":\"success\",\"output\":{" +
            "\"videos\":[{\"file_url\":\"https://example.com/a.mp4\"}]," +
            "\"data\":[{\"video_url\":\"https://example.com/a.mp4\"}," +
            "{\"url\":\"https://example.com/b.mp4\"}]}}";
        GiteeVideoModel model = new GiteeVideoModel(config(), http);

        VideoResponse response = model.getResult("task-gitee");

        assertEquals(2, response.getVideos().size());
        assertEquals("https://example.com/a.mp4", response.getVideos().get(0).getUrl());
        assertEquals("https://example.com/b.mp4", response.getVideos().get(1).getUrl());
    }

    private GiteeVideoModelConfig config() {
        GiteeVideoModelConfig config = new GiteeVideoModelConfig();
        config.setApiKey("test-key");
        return config;
    }

    private static class StubAgentsFlexHttpClient extends AgentsFlexHttpClient {
        String url;
        String payload;
        Map<String, Object> multipart;
        Map<String, String> headers = Collections.emptyMap();
        String queryResponse = "{\"task_id\":\"task-gitee\",\"status\":\"success\",\"price\":null,\"output\":{" +
            "\"video_url\":\"https://example.com/generated.mp4\",\"width\":1280}}";

        @Override
        public String post(String url, Map<String, String> headers, String payload) {
            this.url = url;
            this.headers = headers;
            this.payload = payload;
            return "{\"task_id\":\"task-gitee\",\"status\":\"waiting\"," +
                "\"created_at\":\"2026-07-14T00:00:00Z\",\"urls\":{}}";
        }

        @Override
        public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
            this.url = url;
            this.headers = headers;
            this.multipart = payload;
            return "{\"task_id\":\"task-gitee\",\"status\":\"waiting\"," +
                "\"created_at\":\"2026-07-14T00:00:00Z\",\"urls\":{}}";
        }

        @Override
        public String get(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
            return queryResponse;
        }
    }
}

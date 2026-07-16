//package com.agentsflex.video.aliyun;
//
//import com.agentsflex.core.model.client.AgentsFlexHttpClient;
//import com.agentsflex.core.model.image.Image;
//import com.agentsflex.core.model.video.GenerateVideoRequest;
//import com.agentsflex.core.model.video.VideoResponse;
//import com.agentsflex.core.model.video.VideoTaskStatus;
//import com.alibaba.fastjson2.JSON;
//import com.alibaba.fastjson2.JSONObject;
//import org.junit.Test;
//
//import java.util.Collections;
//import java.util.Map;
//
//import static org.junit.Assert.*;
//
//public class AliyunWanVideoModelTest {
//    @Test
//    public void shouldSubmitAndQueryVideoTask() {
//        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
//        AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();
//        config.setApiKey("test-key");
//        assertTrue(config.isSupportTextToVideo());
//        assertTrue(config.isSupportNegativePrompt());
//        assertTrue(config.isSupportPromptExtend());
//        AliyunWanVideoModel model = new AliyunWanVideoModel(config, http);
//
//        GenerateVideoRequest request = new GenerateVideoRequest();
//        request.setModel(AliyunVideoModels.WAN_2_1_KF2V_PLUS);
//        request.setPrompt("A cinematic camera move");
//        request.setFirstFrame(Image.ofUrl("https://example.com/first.png"));
//        request.setLastFrame(Image.ofUrl("https://example.com/last.png"));
//        request.setSize(1280, 720);
//        request.setDuration(5);
//        request.setPromptExtend(true);
//        request.addOption("parameters", Collections.singletonMap("shot_type", "single"));
//
//        VideoResponse submitted = model.generate(request);
//        assertEquals("task-aliyun", submitted.getTaskId());
//        assertEquals(VideoTaskStatus.QUEUED, submitted.getStatus());
//        assertEquals("enable", http.headers.get("X-DashScope-Async"));
//
//        JSONObject payload = JSON.parseObject(http.payload);
//        assertEquals(AliyunVideoModels.WAN_2_1_KF2V_PLUS, payload.getString("model"));
//        assertEquals("https://example.com/first.png", payload.getJSONObject("input").getString("first_frame_url"));
//        assertEquals("https://example.com/last.png", payload.getJSONObject("input").getString("last_frame_url"));
//        assertEquals("1280*720", payload.getJSONObject("parameters").getString("size"));
//        assertEquals("single", payload.getJSONObject("parameters").getString("shot_type"));
//
//        VideoResponse result = model.getResult("task-aliyun");
//        assertEquals(VideoTaskStatus.SUCCEEDED, result.getStatus());
//        assertEquals("https://example.com/video.mp4", result.getVideo().getUrl());
//    }
//
//    @Test
//    public void shouldRejectHappyHorseModel() {
//        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
//        AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();
//        config.setApiKey("test-key");
//        AliyunWanVideoModel model = new AliyunWanVideoModel(config, http);
//
//        GenerateVideoRequest request = new GenerateVideoRequest();
//        request.setModel(AliyunVideoModels.HAPPYHORSE_1_1_T2V);
//        request.setPrompt("A cinematic scene");
//
//        VideoResponse response = model.generate(request);
//
//        assertTrue(response.isError());
//        assertEquals("InvalidParameter", response.getErrorCode());
//        assertNull(http.payload);
//    }
//
//    private static class StubAgentsFlexHttpClient extends AgentsFlexHttpClient {
//        String payload;
//        Map<String, String> headers;
//
//        @Override
//        public String post(String url, Map<String, String> headers, String payload) {
//            this.headers = headers;
//            this.payload = payload;
//            return "{\"request_id\":\"request-1\",\"output\":{" +
//                "\"task_id\":\"task-aliyun\",\"task_status\":\"PENDING\"}}";
//        }
//
//        @Override
//        public String get(String url, Map<String, String> headers) {
//            return "{\"request_id\":\"request-2\",\"output\":{" +
//                "\"task_id\":\"task-aliyun\",\"task_status\":\"SUCCEEDED\"," +
//                "\"video_url\":\"https://example.com/video.mp4\"}}";
//        }
//    }
//}

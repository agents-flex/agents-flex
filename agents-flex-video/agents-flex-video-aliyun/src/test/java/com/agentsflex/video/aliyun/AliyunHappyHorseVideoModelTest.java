package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.Video;
import com.agentsflex.core.model.video.VideoResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class AliyunHappyHorseVideoModelTest {

    @Test
    public void shouldBuildTextToVideoPayload() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        AliyunHappyHorseVideoModel model = model(http);

        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(AliyunVideoModels.HAPPYHORSE_1_1_T2V);
        request.setPrompt("A miniature cardboard city comes alive at night");
        request.setResolution("720P");
        request.setAspectRatio("16:9");
        request.setDuration(5);
        request.setWatermark(false);
        request.setSeed(42);
        request.setPromptExtend(true);

        VideoResponse response = model.generate(request);

        assertFalse(response.isError());
        JSONObject payload = JSON.parseObject(http.payload);
        JSONObject input = payload.getJSONObject("input");
        JSONObject parameters = payload.getJSONObject("parameters");
        assertEquals(AliyunVideoModels.HAPPYHORSE_1_1_T2V, payload.getString("model"));
        assertEquals(request.getPrompt(), input.getString("prompt"));
        assertFalse(input.containsKey("media"));
        assertEquals("720P", parameters.getString("resolution"));
        assertEquals("16:9", parameters.getString("ratio"));
        assertEquals(5, parameters.getIntValue("duration"));
        assertFalse(parameters.getBooleanValue("watermark"));
        assertEquals(42, parameters.getIntValue("seed"));
        assertFalse(parameters.containsKey("prompt_extend"));
    }

    @Test
    public void shouldBuildFirstFrameImageToVideoPayload() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        AliyunHappyHorseVideoModel model = model(http);

        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(AliyunVideoModels.HAPPYHORSE_1_1_I2V);
        request.setFirstFrame(Image.ofUrl("https://example.com/first.png"));
        request.setResolution("1080P");
        request.setAspectRatio("9:16");
        request.setDuration(5);

        VideoResponse response = model.generate(request);

        assertFalse(response.isError());
        JSONObject payload = JSON.parseObject(http.payload);
        JSONObject input = payload.getJSONObject("input");
        JSONArray media = input.getJSONArray("media");
        assertFalse(input.containsKey("prompt"));
        assertEquals(1, media.size());
        assertEquals("first_frame", media.getJSONObject(0).getString("type"));
        assertEquals("https://example.com/first.png", media.getJSONObject(0).getString("url"));
        assertFalse(payload.getJSONObject("parameters").containsKey("ratio"));
    }

    @Test
    public void shouldBuildReferenceToVideoPayloadInReferenceOrder() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        AliyunHappyHorseVideoModel model = model(http);

        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(AliyunVideoModels.HAPPYHORSE_1_1_R2V);
        request.setPrompt("[Image 1] wears the accessory from [Image 2]");
        request.addReferenceImage(Image.ofUrl("https://example.com/person.jpg"));
        request.addReferenceImage(Image.ofUrl("https://example.com/accessory.jpg"));
        request.setResolution("720P");
        request.setAspectRatio("16:9");

        VideoResponse response = model.generate(request);

        assertFalse(response.isError());
        JSONArray media = JSON.parseObject(http.payload).getJSONObject("input").getJSONArray("media");
        assertEquals(2, media.size());
        assertEquals("reference_image", media.getJSONObject(0).getString("type"));
        assertEquals("https://example.com/person.jpg", media.getJSONObject(0).getString("url"));
        assertEquals("https://example.com/accessory.jpg", media.getJSONObject(1).getString("url"));
    }

    @Test
    public void shouldBuildVideoEditPayloadAndMergeAudioSetting() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        AliyunHappyHorseVideoModel model = model(http);

        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(AliyunVideoModels.HAPPYHORSE_1_0_VIDEO_EDIT);
        request.setPrompt("Replace the character's clothes with the reference image");
        request.setSourceVideo(Video.ofUrl("https://example.com/source.mp4"));
        request.addReferenceImage(Image.ofUrl("https://example.com/clothes.webp"));
        request.setResolution("720P");
        request.setDuration(10);
        request.addOption("parameters", Collections.singletonMap("audio_setting", "origin"));

        VideoResponse response = model.generate(request);

        assertFalse(response.isError());
        JSONObject payload = JSON.parseObject(http.payload);
        JSONArray media = payload.getJSONObject("input").getJSONArray("media");
        assertEquals(2, media.size());
        assertEquals("video", media.getJSONObject(0).getString("type"));
        assertEquals("https://example.com/source.mp4", media.getJSONObject(0).getString("url"));
        assertEquals("reference_image", media.getJSONObject(1).getString("type"));
        assertEquals("origin", payload.getJSONObject("parameters").getString("audio_setting"));
        assertFalse(payload.getJSONObject("parameters").containsKey("duration"));
    }

    @Test
    public void shouldRejectMoreThanNineReferenceImages() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        AliyunHappyHorseVideoModel model = model(http);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(AliyunVideoModels.HAPPYHORSE_1_1_R2V);
        request.setPrompt("Create a video from references");
        for (int i = 0; i < 10; i++) {
            request.addReferenceImage(Image.ofUrl("https://example.com/ref-" + i + ".jpg"));
        }

        VideoResponse response = model.generate(request);

        assertTrue(response.isError());
        assertEquals("InvalidParameter", response.getErrorCode());
        assertEquals(0, http.postCount);
    }

    @Test
    public void shouldRejectVideoEditWithoutSourceVideo() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        AliyunHappyHorseVideoModel model = model(http);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(AliyunVideoModels.HAPPYHORSE_1_0_VIDEO_EDIT);
        request.setPrompt("Edit the source video");

        VideoResponse response = model.generate(request);

        assertTrue(response.isError());
        assertEquals("InvalidParameter", response.getErrorCode());
        assertEquals(0, http.postCount);
    }

    @Test
    public void shouldRejectMoreThanFiveVideoEditReferenceImages() {
        StubAgentsFlexHttpClient http = new StubAgentsFlexHttpClient();
        AliyunHappyHorseVideoModel model = model(http);
        GenerateVideoRequest request = new GenerateVideoRequest();
        request.setModel(AliyunVideoModels.HAPPYHORSE_1_0_VIDEO_EDIT);
        request.setPrompt("Edit the source video");
        request.setSourceVideo(Video.ofUrl("https://example.com/source.mp4"));
        for (int i = 0; i < 6; i++) {
            request.addReferenceImage(Image.ofUrl("https://example.com/ref-" + i + ".jpg"));
        }

        VideoResponse response = model.generate(request);

        assertTrue(response.isError());
        assertEquals("InvalidParameter", response.getErrorCode());
        assertEquals(0, http.postCount);
    }

    private AliyunHappyHorseVideoModel model(StubAgentsFlexHttpClient http) {
        AliyunHappyHorseVideoModelConfig config = new AliyunHappyHorseVideoModelConfig();
        config.setApiKey("test-key");
        return new AliyunHappyHorseVideoModel(config, http);
    }

    private static class StubAgentsFlexHttpClient extends AgentsFlexHttpClient {
        String payload;
        int postCount;

        @Override
        public String post(String url, Map<String, String> headers, String payload) {
            this.payload = payload;
            this.postCount++;
            return "{\"request_id\":\"request-1\",\"output\":{" +
                "\"task_id\":\"task-happyhorse\",\"task_status\":\"PENDING\"}}";
        }
    }
}

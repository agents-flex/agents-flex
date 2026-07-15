package com.agentsflex.video.volcengine;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.video.BaseVideoModel;
import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.Video;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VolcengineVideoModel extends BaseVideoModel<VolcengineVideoModelConfig> {
    private final AgentsFlexHttpClient agentsFlexHttpClient;

    public VolcengineVideoModel(VolcengineVideoModelConfig config) {
        this(config, new AgentsFlexHttpClient());
    }

    VolcengineVideoModel(VolcengineVideoModelConfig config, AgentsFlexHttpClient agentsFlexHttpClient) {
        super(config);
        this.agentsFlexHttpClient = agentsFlexHttpClient;
    }

    @Override
    public VideoResponse generate(GenerateVideoRequest request) {
        if (request == null) return VideoResponse.error("request must not be null");
        String model = StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
        if (StringUtil.noText(model)) return VideoResponse.error("video model must not be empty");

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("content", buildContent(request));
        putIfNotNull(payload, "duration", request.getDuration());
        putIfNotEmpty(payload, "resolution", request.getResolution());
        putIfNotEmpty(payload, "ratio", request.getAspectRatio());
        putIfNotNull(payload, "fps", request.getFps());
        putIfNotNull(payload, "seed", request.getSeed());
        putIfNotNull(payload, "watermark", request.getWatermark());
        putIfNotNull(payload, "camera_fixed", request.getCameraFixed());
        putIfNotNull(payload, "generate_audio", request.getGenerateAudio());
        payload.putAll(request.getOptions());

        String responseJson = agentsFlexHttpClient.post(config.getFullUrl(), headers(), payload.toJSONString());
        return parseResponse(responseJson, true);
    }

    private List<JSONObject> buildContent(GenerateVideoRequest request) {
        List<JSONObject> content = new ArrayList<>();
        if (StringUtil.hasText(request.getPrompt())) {
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", request.getPrompt());
            content.add(text);
        }
        addImage(content, request.getFirstFrame(), "first_frame");
        addImage(content, request.getLastFrame(), "last_frame");
        if (request.getReferenceImages() != null) {
            for (Image image : request.getReferenceImages()) addImage(content, image, "reference_image");
        }
        if (request.getSourceVideo() != null && StringUtil.hasText(request.getSourceVideo().getUrl())) {
            content.add(media("video_url", request.getSourceVideo().getUrl(), null));
        }
        if (StringUtil.hasText(request.getAudioUrl())) {
            content.add(media("audio_url", request.getAudioUrl(), null));
        }
        return content;
    }

    private void addImage(List<JSONObject> content, Image image, String role) {
        if (image != null && StringUtil.hasText(image.getUrlOrBase64())) {
            content.add(media("image_url", image.getUrlOrBase64(), role));
        }
    }

    private JSONObject media(String type, String url, String role) {
        JSONObject item = new JSONObject();
        item.put("type", type);
        JSONObject value = new JSONObject();
        value.put("url", url);
        item.put(type, value);
        if (role != null) item.put("role", role);
        return item;
    }

    @Override
    public VideoResponse getResult(String taskId) {
        if (StringUtil.noText(taskId)) return VideoResponse.error("taskId must not be empty");
        return parseResponse(agentsFlexHttpClient.get(config.getQueryUrl(taskId), headers()), false);
    }

    private VideoResponse parseResponse(String json, boolean submitted) {
        if (StringUtil.noText(json)) return VideoResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return VideoResponse.error("Invalid JSON response: " + json);
        }
        VideoResponse response = new VideoResponse();
        response.setTaskId(root.getString("id"));
        response.setStatus(submitted && root.getString("status") == null
            ? VideoTaskStatus.SUBMITTED : mapStatus(root.getString("status")));
        JSONObject error = root.getJSONObject("error");
        if (error != null) {
            response.setStatus(VideoTaskStatus.FAILED);
            response.setError(true);
            response.setErrorCode(error.getString("code"));
            response.setErrorMessage(error.getString("message"));
        } else if (response.getStatus() == VideoTaskStatus.FAILED) {
            response.setError(true);
            response.setErrorCode(root.getString("code"));
            response.setErrorMessage(root.getString("message"));
        }
        JSONObject content = root.getJSONObject("content");
        if (content != null) {
            addVideo(response, content.getString("video_url"), content);
            JSONArray videos = content.getJSONArray("videos");
            if (videos != null) {
                for (int i = 0; i < videos.size(); i++) {
                    JSONObject video = videos.getJSONObject(i);
                    addVideo(response, firstText(video, "url", "video_url"), video);
                }
            }
        }
        response.setMetadataMap(root);
        return response;
    }

    private void addVideo(VideoResponse response, String url, JSONObject source) {
        if (StringUtil.noText(url)) return;
        Video video = Video.ofUrl(url);
        video.setCoverUrl(firstText(source, "poster_url", "last_frame_url"));
        video.setDuration(source.getInteger("duration"));
        video.setWidth(source.getInteger("width"));
        video.setHeight(source.getInteger("height"));
        response.addVideo(video);
    }

    private VideoTaskStatus mapStatus(String status) {
        if (status == null) return VideoTaskStatus.UNKNOWN;
        String value = status.toLowerCase();
        if ("queued".equals(value) || "pending".equals(value)) return VideoTaskStatus.QUEUED;
        if ("running".equals(value) || "processing".equals(value)) return VideoTaskStatus.RUNNING;
        if ("succeeded".equals(value) || "success".equals(value)) return VideoTaskStatus.SUCCEEDED;
        if ("failed".equals(value)) return VideoTaskStatus.FAILED;
        if ("cancelled".equals(value) || "canceled".equals(value)) return VideoTaskStatus.CANCELED;
        return VideoTaskStatus.UNKNOWN;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtil.hasText(value)) return value;
        }
        return null;
    }

    private static void putIfNotNull(JSONObject object, String key, Object value) {
        if (value != null) object.put(key, value);
    }

    private static void putIfNotEmpty(JSONObject object, String key, String value) {
        if (StringUtil.hasText(value)) object.put(key, value);
    }
}

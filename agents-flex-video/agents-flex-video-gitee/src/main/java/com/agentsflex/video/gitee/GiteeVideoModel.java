package com.agentsflex.video.gitee;

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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Gitee AI 模力方舟视频生成模型。
 * <p>
 * 根据请求素材自动选择文生视频、图生视频、图片视频生成或音频视频生成端点，
 * 并将模力方舟统一的异步任务响应转换为 {@link VideoResponse}。
 */
public class GiteeVideoModel extends BaseVideoModel<GiteeVideoModelConfig> {
    private final AgentsFlexHttpClient agentsFlexHttpClient;

    /**
     * 使用指定配置创建模力方舟视频模型。
     *
     * @param config 访问令牌、默认模型、服务地址和轮询参数
     */
    public GiteeVideoModel(GiteeVideoModelConfig config) {
        this(config, AgentsFlexHttpClient.getDefault());
    }

    GiteeVideoModel(GiteeVideoModelConfig config, AgentsFlexHttpClient agentsFlexHttpClient) {
        super(config);
        if (agentsFlexHttpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.agentsFlexHttpClient = agentsFlexHttpClient;
    }

    /**
     * 提交视频生成任务。请求素材决定实际调用的模力方舟异步端点。
     *
     * @param request 统一视频生成请求
     * @return 包含任务 ID 和当前状态的响应，参数不合法时返回错误响应
     */
    @Override
    public VideoResponse generate(GenerateVideoRequest request) {
        if (request == null) return VideoResponse.error("request must not be null");
        String model = StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
        if (StringUtil.noText(model)) return VideoResponse.error("video model must not be empty");

        Mode mode = resolveMode(request);
        String validationError = validate(mode, request);
        if (validationError != null) {
            return VideoResponse.error("InvalidParameter", validationError);
        }

        String response;
        if (mode == Mode.IMAGE_VIDEO_TO_VIDEO) {
            response = agentsFlexHttpClient.multipartString(config.getImageVideoToVideoUrl(), headers(false),
                buildImageVideoPayload(model, request));
        } else {
            JSONObject payload = buildJsonPayload(mode, model, request);
            response = agentsFlexHttpClient.post(submitUrl(mode), headers(true), payload.toJSONString());
        }
        return parseResponse(response, true);
    }

    /**
     * 查询模力方舟异步任务记录，并在成功时解析视频地址。
     *
     * @param taskId 提交接口返回的任务唯一标识
     * @return 最新任务状态及视频结果
     */
    @Override
    public VideoResponse getResult(String taskId) {
        if (StringUtil.noText(taskId)) return VideoResponse.error("taskId must not be empty");
        return parseResponse(agentsFlexHttpClient.get(config.getQueryUrl(taskId), headers(false)), false);
    }

    private Mode resolveMode(GenerateVideoRequest request) {
        if (request.getSourceVideo() != null && StringUtil.hasText(request.getAudioUrl())) {
            return Mode.AUDIO_VIDEO_TO_VIDEO;
        }
        if (request.getSourceVideo() != null &&
            (request.getFirstFrame() != null || hasReferenceImage(request))) {
            return Mode.IMAGE_VIDEO_TO_VIDEO;
        }
        if (request.getFirstFrame() != null) return Mode.IMAGE_TO_VIDEO;
        return Mode.TEXT_TO_VIDEO;
    }

    private String validate(Mode mode, GenerateVideoRequest request) {
        if (request.getLastFrame() != null) {
            return "Gitee AI video generation does not support lastFrame";
        }
        if (request.getReferenceImages() != null && request.getReferenceImages().size() > 1) {
            return "Gitee AI image-video generation accepts only one reference image";
        }
        if (request.getSourceVideo() == null && hasReferenceImageList(request)) {
            return "Gitee AI referenceImages require sourceVideo";
        }
        if (request.getSourceVideo() == null && StringUtil.hasText(request.getAudioUrl())) {
            return "Gitee AI audio-video generation requires sourceVideo";
        }
        if (mode == Mode.AUDIO_VIDEO_TO_VIDEO &&
            (request.getFirstFrame() != null || hasReferenceImage(request))) {
            return "Gitee AI audio-video generation does not accept reference images";
        }
        if (request.getSourceVideo() != null && mode == Mode.TEXT_TO_VIDEO) {
            return "Gitee AI does not provide a video-only generation endpoint";
        }
        if (mode == Mode.IMAGE_TO_VIDEO) {
            if (StringUtil.noText(request.getPrompt())) {
                return "Gitee AI image-to-video requires prompt";
            }
            if (StringUtil.noText(request.getFirstFrame().getUrlOrBase64())) {
                return "Gitee AI image-to-video requires firstFrame URL or Base64 data";
            }
        }
        if (mode == Mode.IMAGE_VIDEO_TO_VIDEO) {
            Image reference = referenceImage(request);
            if (reference == null || reference.getBytes() == null || reference.getBytes().length == 0) {
                return "Gitee AI image-video generation requires reference image bytes";
            }
            if (request.getSourceVideo().getBytes() == null || request.getSourceVideo().getBytes().length == 0) {
                return "Gitee AI image-video generation requires source video bytes";
            }
        }
        if (mode == Mode.AUDIO_VIDEO_TO_VIDEO &&
            StringUtil.noText(request.getSourceVideo().getUrl())) {
            return "Gitee AI audio-video JSON request requires sourceVideo URL";
        }
        return null;
    }

    private JSONObject buildJsonPayload(Mode mode, String model, GenerateVideoRequest request) {
        JSONObject payload = new JSONObject();
        payload.putAll(request.getOptions());
        payload.put("model", model);
        if (mode == Mode.TEXT_TO_VIDEO) {
            putIfNotEmpty(payload, "prompt", request.getPrompt());
        } else if (mode == Mode.IMAGE_TO_VIDEO) {
            payload.put("prompt", request.getPrompt());
            payload.put("image_url", request.getFirstFrame().getUrlOrBase64());
        } else if (mode == Mode.AUDIO_VIDEO_TO_VIDEO) {
            payload.put("ref_audio", request.getAudioUrl());
            payload.put("ref_video", request.getSourceVideo().getUrl());
        }
        return payload;
    }

    private Map<String, Object> buildImageVideoPayload(String model, GenerateVideoRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.putAll(request.getOptions());
        payload.put("model", model);
        payload.put("ref_image", referenceImage(request).getBytes());
        payload.put("drive_video", request.getSourceVideo().getBytes());
        return payload;
    }

    private String submitUrl(Mode mode) {
        if (mode == Mode.IMAGE_TO_VIDEO) return config.getImageToVideoUrl();
        if (mode == Mode.AUDIO_VIDEO_TO_VIDEO) return config.getAudioVideoToVideoUrl();
        return config.getFullUrl();
    }

    private VideoResponse parseResponse(String json, boolean submitted) {
        if (StringUtil.noText(json)) return VideoResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return VideoResponse.error("Invalid JSON response: " + json);
        }

        JSONObject error = root.getJSONObject("error");
        if (error != null) {
            return VideoResponse.error(firstText(error, "code", "type"), error.getString("message"));
        }

        VideoResponse response = new VideoResponse();
        response.setTaskId(root.getString("task_id"));
        String status = root.getString("status");
        response.setStatus(submitted && status == null
            ? VideoTaskStatus.SUBMITTED : mapStatus(status));
        if (response.getStatus() == VideoTaskStatus.FAILED) {
            response.setError(true);
            response.setErrorCode(firstText(root, "code", "error_code"));
            response.setErrorMessage(firstText(root, "message", "error_message"));
        }

        addVideos(response, root.get("output"), new HashSet<String>());
        response.setMetadataMap(withoutNullValues(root));
        return response;
    }

    private Map<String, Object> withoutNullValues(JSONObject source) {
        Map<String, Object> metadata = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() != null) metadata.put(entry.getKey(), entry.getValue());
        }
        return metadata;
    }

    private void addVideos(VideoResponse response, Object output, Set<String> urls) {
        if (output instanceof String) {
            addVideo(response, (String) output, null, urls);
        } else if (output instanceof JSONObject) {
            JSONObject object = (JSONObject) output;
            addVideo(response, firstText(object, "file_url", "video_url", "url"), object, urls);
            addVideos(response, object.get("videos"), urls);
            addVideos(response, object.get("data"), urls);
            addVideos(response, object.get("output"), urls);
        } else if (output instanceof JSONArray) {
            JSONArray array = (JSONArray) output;
            for (int i = 0; i < array.size(); i++) addVideos(response, array.get(i), urls);
        }
    }

    private void addVideo(VideoResponse response, String url, JSONObject source, Set<String> urls) {
        if (StringUtil.noText(url) || !urls.add(url)) return;
        Video video = Video.ofUrl(url);
        if (source != null) {
            video.setCoverUrl(firstText(source, "cover_url", "poster_url"));
            video.setDuration(source.getInteger("duration"));
            video.setWidth(source.getInteger("width"));
            video.setHeight(source.getInteger("height"));
        }
        response.addVideo(video);
    }

    private VideoTaskStatus mapStatus(String status) {
        if (status == null) return VideoTaskStatus.UNKNOWN;
        String value = status.toLowerCase();
        if ("waiting".equals(value)) return VideoTaskStatus.QUEUED;
        if ("in_progress".equals(value)) return VideoTaskStatus.RUNNING;
        if ("success".equals(value)) return VideoTaskStatus.SUCCEEDED;
        if ("failure".equals(value)) return VideoTaskStatus.FAILED;
        if ("cancelled".equals(value) || "canceled".equals(value)) return VideoTaskStatus.CANCELED;
        return VideoTaskStatus.UNKNOWN;
    }

    private Map<String, String> headers(boolean json) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());
        if (json) headers.put("Content-Type", "application/json");
        return headers;
    }

    private boolean hasReferenceImage(GenerateVideoRequest request) {
        return referenceImage(request) != null;
    }

    private boolean hasReferenceImageList(GenerateVideoRequest request) {
        return request.getReferenceImages() != null && !request.getReferenceImages().isEmpty();
    }

    private Image referenceImage(GenerateVideoRequest request) {
        if (request.getFirstFrame() != null) return request.getFirstFrame();
        if (request.getReferenceImages() == null || request.getReferenceImages().isEmpty()) return null;
        return request.getReferenceImages().get(0);
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtil.hasText(value)) return value;
        }
        return null;
    }

    private static void putIfNotEmpty(JSONObject object, String key, String value) {
        if (StringUtil.hasText(value)) object.put(key, value);
    }

    private enum Mode {
        TEXT_TO_VIDEO,
        IMAGE_TO_VIDEO,
        IMAGE_VIDEO_TO_VIDEO,
        AUDIO_VIDEO_TO_VIDEO
    }
}

package com.agentsflex.video.aliyun;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.model.video.BaseVideoConfig;
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
import java.util.Map;

/**
 * 阿里云视频模型的公共异步任务实现。
 *
 * @param <T> 具体模型族使用的配置类型
 */
abstract class AbstractAliyunVideoModel<T extends BaseVideoConfig> extends BaseVideoModel<T> {
    private final AgentsFlexHttpClient agentsFlexHttpClient;

    protected AbstractAliyunVideoModel(T config) {
        this(config, new AgentsFlexHttpClient());
    }

    protected AbstractAliyunVideoModel(T config, AgentsFlexHttpClient agentsFlexHttpClient) {
        super(config);
        if (agentsFlexHttpClient == null) throw new IllegalArgumentException("httpClient must not be null");
        this.agentsFlexHttpClient = agentsFlexHttpClient;
    }

    @Override
    public final VideoResponse generate(GenerateVideoRequest request) {
        if (request == null) return VideoResponse.error("request must not be null");
        String model = StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
        if (StringUtil.noText(model)) return VideoResponse.error("video model must not be empty");

        String validationError = validate(model, request);
        if (validationError != null) {
            return VideoResponse.error("InvalidParameter", validationError);
        }

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        JSONObject input = buildInput(model, request);
        JSONObject parameters = buildParameters(model, request);
        mergeOptionMap(request, "input", input);
        mergeOptionMap(request, "parameters", parameters);
        payload.put("input", input);
        if (!parameters.isEmpty()) payload.put("parameters", parameters);
        mergeOptionMap(request, "topLevel", payload);

        String json = agentsFlexHttpClient.post(config.getFullUrl(), headers(true), payload.toJSONString());
        return parseResponse(json, true);
    }

    /** 校验具体模型族的请求；合法时返回 {@code null}。 */
    protected abstract String validate(String model, GenerateVideoRequest request);

    /** 按具体模型族协议构造请求的 {@code input} 对象。 */
    protected abstract JSONObject buildInput(String model, GenerateVideoRequest request);

    /** 按具体模型族协议构造请求的 {@code parameters} 对象。 */
    protected abstract JSONObject buildParameters(String model, GenerateVideoRequest request);

    @Override
    public final VideoResponse getResult(String taskId) {
        if (StringUtil.noText(taskId)) return VideoResponse.error("taskId must not be empty");
        return parseResponse(agentsFlexHttpClient.get(config.getQueryUrl(taskId), headers(false)), false);
    }

    protected static void putIfNotNull(JSONObject object, String key, Object value) {
        if (value != null) object.put(key, value);
    }

    protected static void putIfNotEmpty(JSONObject object, String key, String value) {
        if (StringUtil.hasText(value)) object.put(key, value);
    }

    private VideoResponse parseResponse(String json, boolean submitted) {
        if (StringUtil.noText(json)) return VideoResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return VideoResponse.error("Invalid JSON response: " + json);
        }
        if (StringUtil.hasText(root.getString("code"))) {
            return VideoResponse.error(root.getString("code"), root.getString("message"));
        }

        JSONObject output = root.getJSONObject("output");
        if (output == null) return VideoResponse.error("response output is empty: " + json);
        VideoResponse response = new VideoResponse();
        response.setTaskId(output.getString("task_id"));
        response.setStatus(submitted && output.getString("task_status") == null
            ? VideoTaskStatus.SUBMITTED : mapStatus(output.getString("task_status")));
        addVideo(response, output.getString("video_url"), output);
        JSONArray results = output.getJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                addVideo(response, firstText(item, "url", "video_url"), item);
            }
        }
        if (response.getStatus() == VideoTaskStatus.FAILED) {
            response.setError(true);
            response.setErrorCode(firstText(output, "code", "error_code"));
            response.setErrorMessage(firstText(output, "message", "error_message"));
        }
        response.setMetadataMap(root);
        return response;
    }

    private void addVideo(VideoResponse response, String url, JSONObject source) {
        if (StringUtil.noText(url)) return;
        Video video = Video.ofUrl(url);
        video.setCoverUrl(firstText(source, "cover_url", "first_frame_url"));
        video.setDuration(source.getInteger("duration"));
        video.setWidth(source.getInteger("width"));
        video.setHeight(source.getInteger("height"));
        response.addVideo(video);
    }

    private VideoTaskStatus mapStatus(String status) {
        if (status == null) return VideoTaskStatus.UNKNOWN;
        String value = status.toUpperCase();
        if ("PENDING".equals(value) || "QUEUED".equals(value)) return VideoTaskStatus.QUEUED;
        if ("RUNNING".equals(value) || "PROCESSING".equals(value)) return VideoTaskStatus.RUNNING;
        if ("SUCCEEDED".equals(value) || "SUCCESS".equals(value)) return VideoTaskStatus.SUCCEEDED;
        if ("FAILED".equals(value)) return VideoTaskStatus.FAILED;
        if ("CANCELED".equals(value) || "CANCELLED".equals(value)) return VideoTaskStatus.CANCELED;
        return VideoTaskStatus.UNKNOWN;
    }

    private Map<String, String> headers(boolean async) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        if (async) headers.put("X-DashScope-Async", "enable");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private void mergeOptionMap(GenerateVideoRequest request, String key, JSONObject target) {
        Object value = request.getOption(key);
        if (value instanceof Map) target.putAll((Map<String, Object>) value);
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtil.hasText(value)) return value;
        }
        return null;
    }
}

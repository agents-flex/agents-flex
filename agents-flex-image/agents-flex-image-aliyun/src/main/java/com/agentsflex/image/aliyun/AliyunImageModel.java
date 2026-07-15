package com.agentsflex.image.aliyun;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.BaseImageModel;
import com.agentsflex.core.model.image.GenerateImageRequest;
import com.agentsflex.core.model.image.Image;
import com.agentsflex.core.model.image.ImageBoundingBox;
import com.agentsflex.core.model.image.ImageResponse;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Qwen-Image and Wan image generation/editing adapter for Alibaba Model Studio. */
public class AliyunImageModel extends BaseImageModel<AliyunImageModelConfig> {
    private final HttpClient httpClient;

    public AliyunImageModel(AliyunImageModelConfig config) {
        this(config, new HttpClient());
    }

    AliyunImageModel(AliyunImageModelConfig config, HttpClient httpClient) {
        super(config);
        this.httpClient = httpClient;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        if (request == null) return ImageResponse.error("request must not be null");
        String model = StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
        if (StringUtil.noText(model)) return ImageResponse.error("image model must not be empty");

        String boundingBoxError = validateBoundingBoxes(model, request);
        if (boundingBoxError != null) return ImageResponse.error(boundingBoxError);

        boolean taskProtocol = requiresTaskProtocol(model);
        JSONObject payload = buildPayload(model, request);
        String json = httpClient.post(submissionUrl(model, taskProtocol), headers(taskProtocol), payload.toJSONString());
        return taskProtocol ? waitForTask(json) : parseResponse(json);
    }

    private JSONObject buildPayload(String model, GenerateImageRequest request) {
        JSONObject root = new JSONObject();
        root.put("model", model);
        JSONObject input = new JSONObject();
        JSONObject parameters = commonParameters(request);

        if (usesLegacySynthesisPayload(model)) {
            putIfNotEmpty(input, "prompt", request.getPrompt());
            putIfNotEmpty(input, "negative_prompt", request.getNegativePrompt());
            parameters.remove("negative_prompt");
        } else {
            JSONObject message = new JSONObject();
            message.put("role", "user");
            JSONArray content = new JSONArray();
            if (request.getInputImages() != null) {
                for (Image image : request.getInputImages()) {
                    if (image != null && StringUtil.hasText(image.getUrlOrBase64())) {
                        JSONObject item = new JSONObject();
                        item.put("image", image.getUrlOrBase64());
                        content.add(item);
                    }
                }
            }
            if (StringUtil.hasText(request.getPrompt())) {
                JSONObject text = new JSONObject();
                text.put("text", request.getPrompt());
                content.add(text);
            }
            message.put("content", content);
            JSONArray messages = new JSONArray();
            messages.add(message);
            input.put("messages", messages);
        }

        mergeScopedOptions(request.getOptions(), input, parameters, root);
        root.put("input", input);
        if (!parameters.isEmpty()) root.put("parameters", parameters);
        return root;
    }

    private JSONObject commonParameters(GenerateImageRequest request) {
        JSONObject parameters = new JSONObject();
        String size = StringUtil.hasText(request.getResolution()) ? request.getResolution() : toAliyunSize(request.getSizeString());
        putIfNotEmpty(parameters, "size", size);
        putIfNotNull(parameters, "n", request.getN());
        putIfNotEmpty(parameters, "negative_prompt", request.getNegativePrompt());
        putIfNotNull(parameters, "prompt_extend", request.getPromptExtend());
        putIfNotNull(parameters, "watermark", request.getWatermark());
        putIfNotNull(parameters, "seed", request.getSeed());
        if (request.getSequentialGeneration() != null) {
            parameters.put("enable_sequential", request.getSequentialGeneration());
        }
        if (request.getBoundingBoxes() != null && !request.getBoundingBoxes().isEmpty()) {
            parameters.put("bbox_list", toBboxList(request.getBoundingBoxes()));
        }
        return parameters;
    }

    private String validateBoundingBoxes(String model, GenerateImageRequest request) {
        List<List<ImageBoundingBox>> boxes = request.getBoundingBoxes();
        if (boxes == null || boxes.isEmpty()) return null;
        if (!model.toLowerCase().startsWith("wan2.7-image")) {
            return "boundingBoxes are only supported by Wan 2.7 image models";
        }
        int imageCount = request.getInputImages() == null ? 0 : request.getInputImages().size();
        if (boxes.size() != imageCount) {
            return "boundingBoxes must contain one entry for each input image";
        }
        for (List<ImageBoundingBox> imageBoxes : boxes) {
            if (imageBoxes != null && imageBoxes.size() > 2) {
                return "Aliyun supports at most 2 bounding boxes per input image";
            }
            if (imageBoxes == null) continue;
            for (ImageBoundingBox box : imageBoxes) {
                if (box == null || !box.isValid()) {
                    return "boundingBoxes must use non-negative coordinates with x2 > x1 and y2 > y1";
                }
            }
        }
        return null;
    }

    private JSONArray toBboxList(List<List<ImageBoundingBox>> boundingBoxes) {
        JSONArray result = new JSONArray();
        for (List<ImageBoundingBox> imageBoxes : boundingBoxes) {
            JSONArray boxes = new JSONArray();
            if (imageBoxes != null) {
                for (ImageBoundingBox box : imageBoxes) {
                    JSONArray coordinates = new JSONArray();
                    coordinates.add(box.getX1());
                    coordinates.add(box.getY1());
                    coordinates.add(box.getX2());
                    coordinates.add(box.getY2());
                    boxes.add(coordinates);
                }
            }
            result.add(boxes);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void mergeScopedOptions(Map<String, Object> options, JSONObject input, JSONObject parameters, JSONObject root) {
        if (options == null) return;
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if ("input".equals(entry.getKey()) && entry.getValue() instanceof Map) {
                input.putAll((Map<String, Object>) entry.getValue());
            } else if ("parameters".equals(entry.getKey()) && entry.getValue() instanceof Map) {
                parameters.putAll((Map<String, Object>) entry.getValue());
            } else if ("topLevel".equals(entry.getKey()) && entry.getValue() instanceof Map) {
                root.putAll((Map<String, Object>) entry.getValue());
            } else {
                parameters.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private ImageResponse parseResponse(String json) {
        if (StringUtil.noText(json)) return ImageResponse.error("response is empty");
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            return ImageResponse.error("Invalid JSON response: " + json);
        }

        String code = root.getString("code");
        String message = root.getString("message");
        if (StringUtil.hasText(code)) {
            ImageResponse error = ImageResponse.error(message == null ? code : message);
            error.setErrorCode(code);
            error.setMetadataMap(root);
            return error;
        }

        ImageResponse response = new ImageResponse();
        JSONObject output = root.getJSONObject("output");
        if (output != null) {
            addResults(response, output.getJSONArray("results"));
            addChoices(response, output.getJSONArray("choices"));
            if (StringUtil.hasText(output.getString("code"))) {
                response.setError(true);
                response.setErrorCode(output.getString("code"));
                response.setErrorMessage(output.getString("message"));
            }
        }
        response.setMetadataMap(root);
        return response;
    }

    private ImageResponse waitForTask(String submissionJson) {
        ImageResponse submission = parseResponse(submissionJson);
        if (submission.isError() || !submission.getImages().isEmpty()) return submission;

        JSONObject root;
        try {
            root = JSON.parseObject(submissionJson);
        } catch (Exception e) {
            return ImageResponse.error("Invalid JSON response: " + submissionJson);
        }
        JSONObject output = root.getJSONObject("output");
        String taskId = output == null ? null : output.getString("task_id");
        if (StringUtil.noText(taskId)) return ImageResponse.error("Aliyun did not return a task id");
        if (config.getTimeoutMillis() <= 0 || config.getPollIntervalMillis() <= 0) {
            return ImageResponse.error("Aliyun polling timeout and interval must be greater than 0");
        }

        long deadline = System.currentTimeMillis() + config.getTimeoutMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.min(config.getPollIntervalMillis(),
                    Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ImageResponse.error("Interrupted while waiting for Aliyun image generation");
            }

            String resultJson = httpClient.get(config.getQueryUrl(taskId), headers(false));
            ImageResponse response = parseResponse(resultJson);
            if (response.isError() || !response.getImages().isEmpty()) return response;

            JSONObject resultRoot;
            try {
                resultRoot = JSON.parseObject(resultJson);
            } catch (Exception e) {
                return ImageResponse.error("Invalid JSON response: " + resultJson);
            }
            JSONObject resultOutput = resultRoot.getJSONObject("output");
            String status = resultOutput == null ? null : resultOutput.getString("task_status");
            if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status) ||
                "CANCELLED".equalsIgnoreCase(status)) {
                String message = resultOutput == null ? null : resultOutput.getString("message");
                return ImageResponse.error(StringUtil.hasText(message) ? message : "Aliyun image task " + status);
            }
            if ("SUCCEEDED".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status)) {
                return ImageResponse.error("Aliyun image task succeeded without image data");
            }
        }
        return ImageResponse.error("Timed out waiting for Aliyun image generation");
    }

    private void addResults(ImageResponse response, JSONArray results) {
        if (results == null) return;
        for (int i = 0; i < results.size(); i++) {
            JSONObject result = results.getJSONObject(i);
            addUrl(response, firstText(result, "url", "image"));
        }
    }

    private void addChoices(ImageResponse response, JSONArray choices) {
        if (choices == null) return;
        for (int i = 0; i < choices.size(); i++) {
            JSONObject message = choices.getJSONObject(i).getJSONObject("message");
            JSONArray content = message == null ? null : message.getJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.size(); j++) {
                addUrl(response, firstText(content.getJSONObject(j), "image", "url"));
            }
        }
    }

    private void addUrl(ImageResponse response, String url) {
        if (StringUtil.hasText(url)) response.addImage(url);
    }

    private boolean requiresTaskProtocol(String model) { return usesLegacySynthesisPayload(model); }

    private String submissionUrl(String model, boolean async) {
        if (!async) return config.getFullUrl();
        String value = model.toLowerCase();
        if (value.equals("qwen-image") || value.startsWith("qwen-image-plus") || value.startsWith("qwen-image-max")) {
            return config.getQwenSynthesisUrl();
        }
        return config.getAsyncUrl();
    }

    private boolean usesLegacySynthesisPayload(String model) {
        String value = model.toLowerCase();
        if (value.startsWith("wanx2.") || value.startsWith("wan2.1-") || value.startsWith("wan2.2-") ||
            value.startsWith("wan2.5-")) return true;
        return value.equals("qwen-image") || value.startsWith("qwen-image-plus") || value.startsWith("qwen-image-max");
    }

    private Map<String, String> headers(boolean async) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        if (async) headers.put("X-DashScope-Async", "enable");
        return headers;
    }

    private static String toAliyunSize(String size) {
        return size == null ? null : size.replace('x', '*');
    }

    private static String firstText(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            String value = object.getString(key);
            if (StringUtil.hasText(value)) return value;
        }
        return null;
    }

    private static void putIfNotEmpty(JSONObject object, String key, String value) {
        if (StringUtil.hasText(value)) object.put(key, value);
    }

    private static void putIfNotNull(JSONObject object, String key, Object value) {
        if (value != null) object.put(key, value);
    }
}

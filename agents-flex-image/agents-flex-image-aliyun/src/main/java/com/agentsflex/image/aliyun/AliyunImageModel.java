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

/**
 * 阿里云百炼图片模型适配器，统一支持千问图片模型与通义万相图片模型。
 * <p>
 * 对外始终实现同步 {@link #generate(GenerateImageRequest)}：新模型直接调用同步多模态接口；
 * 仅提供异步协议的旧模型会在本类内部提交任务并轮询，直到获得最终图片或超时。
 * </p>
 */
public class AliyunImageModel extends BaseImageModel<AliyunImageModelConfig> {
    /** 执行百炼 HTTP 请求的客户端；测试可通过包级构造方法注入替身。 */
    private final HttpClient httpClient;

    /**
     * 使用默认 HTTP 客户端创建阿里云图片模型。
     *
     * @param config 阿里云图片模型配置
     */
    public AliyunImageModel(AliyunImageModelConfig config) {
        this(config, new HttpClient());
    }

    /** 供同包测试注入 HTTP 客户端，避免单元测试访问真实服务。 */
    AliyunImageModel(AliyunImageModelConfig config, HttpClient httpClient) {
        super(config);
        this.httpClient = httpClient;
    }

    /**
     * 同步生成或编辑图片。
     * <p>请求模型优先于配置模型；方法会根据模型族自动选择同步多模态协议或内部任务轮询协议。</p>
     */
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

    /**
     * 按模型协议构建百炼请求体。
     * <p>旧图片合成协议使用普通 prompt 字段；新多模态协议使用 messages/content 结构。</p>
     */
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

    /** 将核心请求中的尺寸、数量、水印、随机种子和框选区域映射到 parameters。 */
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

    /**
     * 校验万相 2.7 的框选区域约束。
     *
     * @return 校验失败信息；校验通过时返回 {@code null}
     */
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

    /** 将核心层的矩形对象转换为百炼 {@code bbox_list} 坐标数组。 */
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

    /**
     * 合并供应商扩展参数。
     * <p>{@code input}、{@code parameters}、{@code topLevel} 三个特殊键可定向合并；
     * 其他键默认写入 parameters。</p>
     */
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

    /** 解析同步响应或任务查询响应，并保留供应商原始字段作为元数据。 */
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

    /**
     * 在适配器内部等待异步任务完成。
     * <p>轮询间隔和总超时时间由配置控制；线程被中断时会恢复中断标记并返回错误响应。</p>
     */
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

    /** 判断指定模型是否只能通过任务协议获取最终图片。 */
    private boolean requiresTaskProtocol(String model) { return usesLegacySynthesisPayload(model); }

    /** 根据模型族选择同步接口、旧千问合成接口或旧万相异步接口。 */
    private String submissionUrl(String model, boolean async) {
        if (!async) return config.getFullUrl();
        String value = model.toLowerCase();
        if (value.equals("qwen-image") || value.startsWith("qwen-image-plus") || value.startsWith("qwen-image-max")) {
            return config.getQwenSynthesisUrl();
        }
        return config.getAsyncUrl();
    }

    /** 判断模型是否使用旧版图片合成请求结构。 */
    private boolean usesLegacySynthesisPayload(String model) {
        String value = model.toLowerCase();
        if (value.startsWith("wanx2.") || value.startsWith("wan2.1-") || value.startsWith("wan2.2-") ||
            value.startsWith("wan2.5-")) return true;
        return value.equals("qwen-image") || value.startsWith("qwen-image-plus") || value.startsWith("qwen-image-max");
    }

    /** 构建鉴权请求头；异步任务提交时额外携带 {@code X-DashScope-Async}。 */
    private Map<String, String> headers(boolean async) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        if (async) headers.put("X-DashScope-Async", "enable");
        return headers;
    }

    /** 将核心层的 {@code 宽x高} 转为旧百炼接口要求的 {@code 宽*高}。 */
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

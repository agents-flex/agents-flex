package com.agentsflex.image.bailian;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.*;
import com.agentsflex.core.util.JSONUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class BailianImageModel implements ImageModel {

    private static final Logger LOG = LoggerFactory.getLogger(BailianImageModel.class);
    private final BailianImageModelConfig config;
    private final HttpClient httpClient = new HttpClient();

    public BailianImageModel(BailianImageModelConfig config) {
        this.config = config;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = Maps.of("model", config.getModel())
            .set("parameters", Maps.of().setIfNotEmpty("size", request.getSizeString())
                    .set("n", request.getN())
//                .set("seed",request.get)
            )
            .set("input", Maps.of("messages", Collections.singletonList(Maps.of("role", "user").set("content", Collections.singletonList(
                Maps.of("text", request.getPrompt())
            )))))
            .toJSON();


        String responseJson = httpClient.post(config.getUrl(), headers, payload);

        if (StringUtil.noText(responseJson)) {
            return ImageResponse.error("response is no text");
        }

        JSONObject root = JSON.parseObject(responseJson);
        JSONArray imageObjects = JSONUtil.getJSONArray(root, "output.choices[0].message.content");
        if (imageObjects == null || imageObjects.isEmpty()) {
            return ImageResponse.error("content data is empty: " + responseJson);
        }

        ImageResponse response = new ImageResponse();
        for (int i = 0; i < imageObjects.size(); i++) {
            JSONObject imageObj = imageObjects.getJSONObject(i);
            if ("image".equals(imageObj.getString("type"))) {
                response.addImage(imageObj.getString("image"));
            }
        }

        response.putMetadata(root.getJSONObject("usage"));
        return response;
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        throw new IllegalStateException("BailianImageModel Can not support img2imggenerate.");
    }

    @Override
    public ImageResponse edit(EditImageRequest request) {
        throw new IllegalStateException("BailianImageModel Can not support edit image.");
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        throw new IllegalStateException("BailianImageModel Can not support vary image.");
    }
}

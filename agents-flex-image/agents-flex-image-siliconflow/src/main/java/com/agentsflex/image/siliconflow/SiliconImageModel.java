/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.image.siliconflow;

import com.agentsflex.core.image.*;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class SiliconImageModel implements ImageModel {
    private SiliconflowImageModelConfig config;
    private HttpClient httpClient = new HttpClient();

    public SiliconImageModel(SiliconflowImageModelConfig config) {
        this.config = config;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = Maps.of("prompt", request.getPrompt())
            .setIfNotEmpty("negative_prompt", request.getNegativePrompt())
            .setOrDefault("image_size", request.getSize(), config.getImageSize())
            .setOrDefault("batch_size", request.getN(), 1)
            .setOrDefault("num_inference_steps", request.getOption("num_inference_steps"), config.getNumInferenceSteps())
            .setOrDefault("guidance_scale", request.getOption("guidance_scale"), config.getGuidanceScale())
            .toJSON();

        String url = config.getEndpoint() + SiliconflowImageModels.getPath(config.getModel());
        String response = httpClient.post(url, headers, payload);
        if (StringUtil.noText(response)) {
            return ImageResponse.error("response is no text");
        }

        if (StringUtil.notJsonObject(response)) {
            return ImageResponse.error(response);
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONArray imagesArray = jsonObject.getJSONArray("images");
        if (imagesArray == null || imagesArray.isEmpty()) {
            return null;
        }

        ImageResponse imageResponse = new ImageResponse();
        for (int i = 0; i < imagesArray.size(); i++) {
            JSONObject imageObject = imagesArray.getJSONObject(i);
            imageResponse.addImage(imageObject.getString("url"));
        }

        return imageResponse;
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        return null;
    }


    @Override
    public ImageResponse edit(EditImageRequest request) {
        throw new IllegalStateException("SiliconImageModel Can not support edit image.");
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        throw new IllegalStateException("SiliconImageModel Can not support vary image.");
    }

}

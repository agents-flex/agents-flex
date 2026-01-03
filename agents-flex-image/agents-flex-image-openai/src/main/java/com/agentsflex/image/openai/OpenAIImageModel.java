/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.image.openai;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.*;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class OpenAIImageModel extends BaseImageModel<OpenAIImageModelConfig> {

    private OpenAIImageModelConfig config;
    private HttpClient httpClient = new HttpClient();

    public OpenAIImageModel(OpenAIImageModelConfig config) {
        super(config);
        this.config = config;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = Maps.of("model", config.getModel())
            .set("prompt", request.getPrompt())
            .setIfNotNull("n", request.getN())
            .set("size", request.getSize())
            .toJSON();


        String url = config.getEndpoint() + "/v1/images/generations";
        String responseJson = httpClient.post(url, headers, payload);

        if (StringUtil.noText(responseJson)) {
            return ImageResponse.error("response is no text");
        }

        JSONObject root = JSON.parseObject(responseJson);
        JSONArray images = root.getJSONArray("data");
        if (images == null || images.isEmpty()) {
            return ImageResponse.error("image data is empty: " + responseJson);
        }
        ImageResponse response = new ImageResponse();
        for (int i = 0; i < images.size(); i++) {
            JSONObject imageObj = images.getJSONObject(i);
            response.addImage(imageObj.getString("url"));
        }

        return response;
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        return null;
    }


    @Override
    public ImageResponse edit(EditImageRequest request) {
        throw new UnsupportedOperationException("not support edit image");
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        throw new UnsupportedOperationException("not support vary image");
    }

}

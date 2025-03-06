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
package com.agentsflex.image.gitee;

import com.agentsflex.core.image.*;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.util.Maps;

import java.util.HashMap;
import java.util.Map;

public class GiteeImageModel implements ImageModel {
    private GiteeImageModelConfig config;
    private HttpClient httpClient = new HttpClient();

    public GiteeImageModel(GiteeImageModelConfig config) {
        this.config = config;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = Maps.of("inputs", request.getPrompt())
            .setIfNotNull("width", request.getWidth())
            .setIfNotNull("height", request.getHeight())
            .toJSON();

        String url = config.getEndpoint() + "/api/serverless/" + config.getModel() + "/text-to-image";
        byte[] imageBytes = httpClient.postBytes(url, headers, payload);
        if (imageBytes == null || imageBytes.length == 0) {
            return ImageResponse.error("can not read the image bytes.");
        }

        ImageResponse response = new ImageResponse();
        response.addImage(imageBytes);

        return response;
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        return null;
    }


    @Override
    public ImageResponse edit(EditImageRequest request) {
        throw new IllegalStateException("GiteeImageModel Can not support edit image.");
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        throw new IllegalStateException("GiteeImageModel Can not support vary image.");
    }

}

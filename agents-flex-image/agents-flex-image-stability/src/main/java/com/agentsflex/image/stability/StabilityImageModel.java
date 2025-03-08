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
package com.agentsflex.image.stability;

import com.agentsflex.core.image.*;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.util.Maps;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class StabilityImageModel implements ImageModel {
    private static final Logger LOG = LoggerFactory.getLogger(StabilityImageModel.class);
    private StabilityImageModelConfig config;
    private HttpClient httpClient = new HttpClient();

    public StabilityImageModel(StabilityImageModelConfig config) {
        this.config = config;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "image/*");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        Map<String, Object> payload = Maps.of("prompt", request.getPrompt())
            .setIfNotNull("output_format", "jpeg");

        String url = config.getEndpoint() + "/v2beta/stable-image/generate/sd3";

        try (Response response = httpClient.multipart(url, headers, payload);
             ResponseBody body = response.body()) {
            if (response.isSuccessful() && body != null) {
                ImageResponse imageResponse = new ImageResponse();
                imageResponse.addImage(body.bytes());
                return imageResponse;
            }
        } catch (IOException e) {
            LOG.error(e.toString(), e);
        }

        return null;
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        return null;
    }

    @Override
    public ImageResponse edit(EditImageRequest request) {
        return null;
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        return null;
    }
}

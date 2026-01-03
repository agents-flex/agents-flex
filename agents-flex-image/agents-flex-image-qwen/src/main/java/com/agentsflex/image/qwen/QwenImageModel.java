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
package com.agentsflex.image.qwen;

import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.image.*;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

public class QwenImageModel implements ImageModel {
    private static final Logger LOG = LoggerFactory.getLogger(QwenImageModel.class);
    private final QwenImageModelConfig config;
    private final HttpClient httpClient = new HttpClient();

    public QwenImageModel(QwenImageModelConfig config) {
        this.config = config;
    }

    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        try {
            ImageSynthesis is = new ImageSynthesis();
            ImageSynthesisParam param =
                ImageSynthesisParam.builder()
                    .apiKey(config.getApiKey())
                    .model(null != request.getModel() ? request.getModel() : config.getModel())
                    .size(request.getSize())
                    .prompt(request.getPrompt())
                    .seed(Integer.valueOf(String.valueOf(request.getOptionOrDefault("seed",1))))
                    .build();
            ImageSynthesisResult result = is.call(param);
            if (Objects.isNull(result.getOutput().getResults())){
                return ImageResponse.error(result.getOutput().getMessage());
            }
            ImageResponse imageResponse = new ImageResponse();
            for(Map<String, String> item :result.getOutput().getResults()) {
                imageResponse.addImage(item.get("url"));
            }
            return imageResponse;
        } catch (Exception e) {
            return ImageResponse.error(e.getMessage());
        }
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        throw new IllegalStateException("QwenImageModel Can not support img2imggenerate.");
    }

    @Override
    public ImageResponse edit(EditImageRequest request) {
        throw new IllegalStateException("QwenImageModel Can not support edit image.");
    }

    @Override
    public ImageResponse vary(VaryImageRequest request) {
        throw new IllegalStateException("QwenImageModel Can not support vary image.");
    }


}

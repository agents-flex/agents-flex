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
package com.agentsflex.image.volcengine;

import com.agentsflex.core.model.image.*;
import com.volcengine.ark.runtime.model.images.generation.GenerateImagesRequest;
import com.volcengine.ark.runtime.model.images.generation.ImagesResponse;
import com.volcengine.ark.runtime.model.images.generation.ResponseFormat;
import com.volcengine.ark.runtime.service.ArkService;

import java.util.ArrayList;
import java.util.List;

public class VolcengineImageModel implements ImageModel {

    private VolcengineImageModelConfig config;
    private ArkService arkService;

    public VolcengineImageModel(VolcengineImageModelConfig config) {
        this.config = config;
        this.arkService = new ArkService(config.getAccessKey(), config.getSecretKey());
    }

    private com.agentsflex.core.model.image.ImageResponse processImageRequest(GenerateImageRequest request) {
        GenerateImagesRequest.Builder builder = GenerateImagesRequest.builder()
            .model(request.getModel())
            .prompt(request.getPrompt())
            .size(request.getSizeString())
            .responseFormat(ResponseFormat.Url)
            .stream(false)
            .watermark(true);


        // 参考图
        if (request.getRefImages() != null && !request.getRefImages().isEmpty()) {
            List<Image> refImages = request.getRefImages();
            if (refImages.size() == 1) {
                builder.image(refImages.get(0).getUrlOrBase64());
            } else {
                List<String> images = new ArrayList<>(refImages.size());
                for (Image refImage : refImages) {
                    images.add(refImage.getUrlOrBase64());
                }
                builder.image(images);
            }
        }

        ImagesResponse imagesResponse = arkService.generateImages(builder.build());

        com.agentsflex.core.model.image.ImageResponse result = new ImageResponse();
        ImagesResponse.Error error = imagesResponse.getError();
        if (error != null) {
            return ImageResponse.error(error.getMessage());
        }

        List<ImagesResponse.Image> images = imagesResponse.getData();
        if (images == null || images.isEmpty()) {
            return ImageResponse.error("image data is empty: " + imagesResponse);
        }

        for (ImagesResponse.Image image : images) {
            result.addImage(image.getUrl());
        }

        return result;
    }


    @Override
    public ImageResponse generate(GenerateImageRequest request) {
        return processImageRequest(request);
    }

    @Override
    public ImageResponse img2imggenerate(GenerateImageRequest request) {
        return processImageRequest(request);
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

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
package com.agentsflex.core.model.image;

import java.util.ArrayList;
import java.util.List;

public class GenerateImageRequest extends BaseImageRequest {
    private String prompt;
    private String negativePrompt;
    private String quality;
    private String style;

    // 参考图片
    private List<Image> refImages;

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getNegativePrompt() {
        return negativePrompt;
    }

    public void setNegativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public List<Image> getRefImages() {
        return refImages;
    }

    public void setRefImages(List<Image> refImages) {
        this.refImages = refImages;
    }

    public void addRefImage(Image image) {
        if (this.refImages == null) {
            this.refImages = new ArrayList<>();
        } else {
            this.refImages.add(image);
        }
    }

    public void removeRefImage(Image image) {
        if (this.refImages != null) {
            this.refImages.remove(image);
        }
    }
}


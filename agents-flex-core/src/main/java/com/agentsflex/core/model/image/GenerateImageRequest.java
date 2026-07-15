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
    private Integer seed;
    private Boolean watermark;
    private Boolean promptExtend;
    private String resolution;
    private String outputFormat;
    private Boolean sequentialGeneration;
    private Integer maxImages;

    /** Ordered source/reference images for editing, fusion or image-conditioned generation. */
    private List<Image> inputImages;

    /**
     * Edit regions aligned by index with {@link #inputImages}. An empty inner list means that
     * the corresponding input image has no explicitly selected region.
     */
    private List<List<ImageBoundingBox>> boundingBoxes;

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
        return inputImages;
    }

    public void setRefImages(List<Image> refImages) {
        this.inputImages = refImages;
    }

    public void addRefImage(Image image) {
        addInputImage(image);
    }

    public void removeRefImage(Image image) {
        removeInputImage(image);
    }

    public List<Image> getInputImages() { return inputImages; }
    public void setInputImages(List<Image> inputImages) { this.inputImages = inputImages; }
    public void addInputImage(Image image) {
        if (inputImages == null) inputImages = new ArrayList<>();
        inputImages.add(image);
    }
    public void removeInputImage(Image image) { if (inputImages != null) inputImages.remove(image); }
    public List<List<ImageBoundingBox>> getBoundingBoxes() { return boundingBoxes; }
    public void setBoundingBoxes(List<List<ImageBoundingBox>> boundingBoxes) { this.boundingBoxes = boundingBoxes; }
    public void addBoundingBoxes(List<ImageBoundingBox> imageBoundingBoxes) {
        if (boundingBoxes == null) boundingBoxes = new ArrayList<>();
        boundingBoxes.add(imageBoundingBoxes);
    }
    public Integer getSeed() { return seed; }
    public void setSeed(Integer seed) { this.seed = seed; }
    public Boolean getWatermark() { return watermark; }
    public void setWatermark(Boolean watermark) { this.watermark = watermark; }
    public Boolean getPromptExtend() { return promptExtend; }
    public void setPromptExtend(Boolean promptExtend) { this.promptExtend = promptExtend; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    public Boolean getSequentialGeneration() { return sequentialGeneration; }
    public void setSequentialGeneration(Boolean sequentialGeneration) { this.sequentialGeneration = sequentialGeneration; }
    public Integer getMaxImages() { return maxImages; }
    public void setMaxImages(Integer maxImages) { this.maxImages = maxImages; }
}

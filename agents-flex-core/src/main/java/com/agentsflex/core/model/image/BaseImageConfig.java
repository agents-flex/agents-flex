/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.image;

import com.agentsflex.core.model.config.BaseModelConfig;

import java.util.List;

/** Common connection and capability settings for image models. */
public class BaseImageConfig extends BaseModelConfig {
    protected Boolean supportTextToImage;
    protected Boolean supportImageToImage;
    protected Boolean supportImageEditing;
    protected Boolean supportMultipleInputImages;
    protected Boolean supportMultipleOutputImages;
    protected Boolean supportMask;
    protected Boolean supportNegativePrompt;
    protected Boolean supportPromptExtend;
    protected Boolean supportWatermark;
    protected Boolean supportSeed;
    protected Integer maxInputImages;
    protected Integer maxOutputImages;
    protected List<String> supportedResolutions;

    public Boolean getSupportTextToImage() { return supportTextToImage; }
    public void setSupportTextToImage(Boolean value) { supportTextToImage = value; }
    public boolean isSupportTextToImage() { return Boolean.TRUE.equals(supportTextToImage); }
    public Boolean getSupportImageToImage() { return supportImageToImage; }
    public void setSupportImageToImage(Boolean value) { supportImageToImage = value; }
    public boolean isSupportImageToImage() { return Boolean.TRUE.equals(supportImageToImage); }
    public Boolean getSupportImageEditing() { return supportImageEditing; }
    public void setSupportImageEditing(Boolean value) { supportImageEditing = value; }
    public boolean isSupportImageEditing() { return Boolean.TRUE.equals(supportImageEditing); }
    public Boolean getSupportMultipleInputImages() { return supportMultipleInputImages; }
    public void setSupportMultipleInputImages(Boolean value) { supportMultipleInputImages = value; }
    public boolean isSupportMultipleInputImages() { return Boolean.TRUE.equals(supportMultipleInputImages); }
    public Boolean getSupportMultipleOutputImages() { return supportMultipleOutputImages; }
    public void setSupportMultipleOutputImages(Boolean value) { supportMultipleOutputImages = value; }
    public boolean isSupportMultipleOutputImages() { return Boolean.TRUE.equals(supportMultipleOutputImages); }
    public Boolean getSupportMask() { return supportMask; }
    public void setSupportMask(Boolean value) { supportMask = value; }
    public boolean isSupportMask() { return Boolean.TRUE.equals(supportMask); }
    public Boolean getSupportNegativePrompt() { return supportNegativePrompt; }
    public void setSupportNegativePrompt(Boolean value) { supportNegativePrompt = value; }
    public boolean isSupportNegativePrompt() { return Boolean.TRUE.equals(supportNegativePrompt); }
    public Boolean getSupportPromptExtend() { return supportPromptExtend; }
    public void setSupportPromptExtend(Boolean value) { supportPromptExtend = value; }
    public boolean isSupportPromptExtend() { return Boolean.TRUE.equals(supportPromptExtend); }
    public Boolean getSupportWatermark() { return supportWatermark; }
    public void setSupportWatermark(Boolean value) { supportWatermark = value; }
    public boolean isSupportWatermark() { return Boolean.TRUE.equals(supportWatermark); }
    public Boolean getSupportSeed() { return supportSeed; }
    public void setSupportSeed(Boolean value) { supportSeed = value; }
    public boolean isSupportSeed() { return Boolean.TRUE.equals(supportSeed); }
    public Integer getMaxInputImages() { return maxInputImages; }
    public void setMaxInputImages(Integer maxInputImages) { this.maxInputImages = maxInputImages; }
    public Integer getMaxOutputImages() { return maxOutputImages; }
    public void setMaxOutputImages(Integer maxOutputImages) { this.maxOutputImages = maxOutputImages; }
    public List<String> getSupportedResolutions() { return supportedResolutions; }
    public void setSupportedResolutions(List<String> values) { supportedResolutions = values; }
}

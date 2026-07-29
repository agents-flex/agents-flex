/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.openai;

import com.agentsflex.core.model.image.BaseImageConfig;

import java.util.Arrays;
import java.util.Collections;

/**
 * OpenAI Images API 的连接配置和当前模型能力描述。
 */
public class OpenAIImageModelConfig extends BaseImageConfig {
    private static final java.util.List<String> GPT_IMAGE_RESOLUTIONS =
        Arrays.asList("auto", "1024x1024", "1536x1024", "1024x1536");
    private static final java.util.List<String> GPT_IMAGE_ASPECT_RATIOS =
        Arrays.asList("1:1", "3:2", "2:3");
    private static final java.util.List<String> GPT_IMAGE_QUALITIES =
        Arrays.asList("auto", "low", "medium", "high");
    private static final java.util.List<String> DALL_E_3_RESOLUTIONS =
        Arrays.asList("1024x1024", "1792x1024", "1024x1792");
    private static final java.util.List<String> DALL_E_3_ASPECT_RATIOS =
        Arrays.asList("1:1", "7:4", "4:7");
    private static final java.util.List<String> DALL_E_3_QUALITIES = Arrays.asList("standard", "hd");
    private static final java.util.List<String> DALL_E_2_RESOLUTIONS =
        Arrays.asList("256x256", "512x512", "1024x1024");
    private static final java.util.List<String> DALL_E_2_ASPECT_RATIOS = Collections.singletonList("1:1");
    private static final java.util.List<String> DALL_E_2_QUALITIES = Collections.singletonList("standard");

    /**
     * 初始化 OpenAI 默认端点、生成路径和 GPT Image 1.5 模型能力。
     */
    public OpenAIImageModelConfig() {
        setProvider("openai");
        setEndpoint("https://api.openai.com");
        setRequestPath("/v1/images/generations");
        setSupportTextToImage(true);
        setSupportImageToImage(false);
        setSupportImageEditing(false);
        setSupportMultipleInputImages(false);
        setSupportMultipleOutputImages(true);
        setMaxInputImages(0);
        setMaxOutputImages(10);
        setModel(OpenAIImageModels.GPT_IMAGE_1_5);
    }

    /**
     * 设置模型并同步刷新供产品展示的尺寸、宽高比和画质选项。
     */
    @Override
    public void setModel(String model) {
        super.setModel(model);
        String currentModel = getModel();
        setSupportedResolutions(resolutionsForModel(currentModel));
        setSupportedAspectRatios(aspectRatiosForModel(currentModel));
        setSupportedQualities(qualitiesForModel(currentModel));
    }

    @Override
    public java.util.List<String> getSupportedResolutions(String model) {
        return resolutionsForModel(model);
    }

    @Override
    public java.util.List<String> getSupportedAspectRatios(String model) {
        return aspectRatiosForModel(model);
    }

    @Override
    public java.util.List<String> getSupportedQualities(String model) {
        return qualitiesForModel(model);
    }

    private static java.util.List<String> resolutionsForModel(String model) {
        if (isGptImageModel(model)) return GPT_IMAGE_RESOLUTIONS;
        if (OpenAIImageModels.DALL_E_3.equals(model)) return DALL_E_3_RESOLUTIONS;
        if (OpenAIImageModels.DALL_E_2.equals(model)) return DALL_E_2_RESOLUTIONS;
        return null;
    }

    private static java.util.List<String> aspectRatiosForModel(String model) {
        if (isGptImageModel(model)) return GPT_IMAGE_ASPECT_RATIOS;
        if (OpenAIImageModels.DALL_E_3.equals(model)) return DALL_E_3_ASPECT_RATIOS;
        if (OpenAIImageModels.DALL_E_2.equals(model)) return DALL_E_2_ASPECT_RATIOS;
        return null;
    }

    private static java.util.List<String> qualitiesForModel(String model) {
        if (isGptImageModel(model)) return GPT_IMAGE_QUALITIES;
        if (OpenAIImageModels.DALL_E_3.equals(model)) return DALL_E_3_QUALITIES;
        if (OpenAIImageModels.DALL_E_2.equals(model)) return DALL_E_2_QUALITIES;
        return null;
    }

    static boolean isGptImageModel(String model) {
        return OpenAIImageModels.GPT_IMAGE_1_5.equals(model) ||
            OpenAIImageModels.GPT_IMAGE_1.equals(model) ||
            OpenAIImageModels.GPT_IMAGE_1_MINI.equals(model);
    }
}

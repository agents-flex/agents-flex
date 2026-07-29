/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.gemini;

import com.agentsflex.core.model.image.BaseImageConfig;

import java.util.Arrays;
import java.util.Collections;

/**
 * Gemini generateContent 图片接口的连接配置和当前模型能力描述。
 */
public class GeminiImageModelConfig extends BaseImageConfig {
    private static final java.util.List<String> STANDARD_ASPECT_RATIOS = Arrays.asList(
        "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"
    );
    private static final java.util.List<String> EXTENDED_ASPECT_RATIOS = Arrays.asList(
        "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4",
        "8:1", "9:16", "16:9", "21:9"
    );

    /**
     * 初始化 Gemini v1 端点和推荐的 Nano Banana 2 模型。
     */
    public GeminiImageModelConfig() {
        setProvider("google");
        setEndpoint("https://generativelanguage.googleapis.com");
        setRequestPath("/v1/models/{model}:generateContent");
        setSupportTextToImage(true);
        setSupportImageToImage(true);
        setSupportImageEditing(true);
        setSupportMultipleInputImages(true);
        setSupportMultipleOutputImages(true);
        setModel(GeminiImageModels.GEMINI_3_1_FLASH_IMAGE);
    }

    /**
     * 设置模型并刷新该 Nano Banana 型号的比例、分辨率和输入数量限制。
     */
    @Override
    public void setModel(String model) {
        super.setModel(model);
        String currentModel = getModel();
        setSupportedResolutions(supportedResolutions(currentModel));
        setSupportedAspectRatios(supportedAspectRatios(currentModel));
        if (GeminiImageModels.GEMINI_3_1_FLASH_IMAGE.equals(currentModel)) {
            setMaxInputImages(14);
        } else if (GeminiImageModels.GEMINI_3_1_FLASH_LITE_IMAGE.equals(currentModel)) {
            setMaxInputImages(14);
        } else if (GeminiImageModels.GEMINI_3_PRO_IMAGE.equals(currentModel)) {
            setMaxInputImages(14);
        } else if (GeminiImageModels.GEMINI_2_5_FLASH_IMAGE.equals(currentModel)) {
            setMaxInputImages(3);
        } else {
            setMaxInputImages(null);
        }
        setSupportedQualities(null);
    }

    static java.util.List<String> supportedResolutions(String model) {
        if (GeminiImageModels.GEMINI_3_1_FLASH_IMAGE.equals(model)) {
            return Arrays.asList("512", "1K", "2K", "4K");
        }
        if (GeminiImageModels.GEMINI_3_PRO_IMAGE.equals(model)) return Arrays.asList("1K", "2K", "4K");
        if (GeminiImageModels.GEMINI_3_1_FLASH_LITE_IMAGE.equals(model) ||
            GeminiImageModels.GEMINI_2_5_FLASH_IMAGE.equals(model)) return Collections.singletonList("1K");
        return null;
    }

    static java.util.List<String> supportedAspectRatios(String model) {
        if (GeminiImageModels.GEMINI_3_1_FLASH_IMAGE.equals(model) ||
            GeminiImageModels.GEMINI_3_1_FLASH_LITE_IMAGE.equals(model)) return EXTENDED_ASPECT_RATIOS;
        if (GeminiImageModels.GEMINI_3_PRO_IMAGE.equals(model) ||
            GeminiImageModels.GEMINI_2_5_FLASH_IMAGE.equals(model)) return STANDARD_ASPECT_RATIOS;
        return null;
    }

    /**
     * 根据实际请求模型生成 generateContent 地址。
     */
    public String getGenerateUrl(String model) {
        String path = getRequestPath() == null ? "" : getRequestPath().replace("{model}", model);
        return (getEndpoint() == null ? "" : getEndpoint()) + path;
    }
}

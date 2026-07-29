/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.gemini;

/**
 * Google Gemini API 中 Nano Banana 系列图片模型标识。
 */
public final class GeminiImageModels {
    /**
     * Nano Banana 2，通用场景的推荐模型。
     */
    public static final String GEMINI_3_1_FLASH_IMAGE = "gemini-3.1-flash-image";
    /**
     * Nano Banana 2 Lite，面向低延迟和低成本场景。
     */
    public static final String GEMINI_3_1_FLASH_LITE_IMAGE = "gemini-3.1-flash-lite-image";
    /**
     * Nano Banana Pro，面向专业素材和复杂指令。
     */
    public static final String GEMINI_3_PRO_IMAGE = "gemini-3-pro-image";
    /**
     * 第一代 Nano Banana 模型。
     */
    public static final String GEMINI_2_5_FLASH_IMAGE = "gemini-2.5-flash-image";

    private GeminiImageModels() {
    }
}

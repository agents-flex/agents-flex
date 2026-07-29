/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.openai;

/**
 * OpenAI Images API 支持的常用模型标识。
 */
public final class OpenAIImageModels {
    /**
     * 当前推荐的高质量 GPT Image 模型。
     */
    public static final String GPT_IMAGE_1_5 = "gpt-image-1.5";
    /**
     * GPT Image 1 模型。
     */
    public static final String GPT_IMAGE_1 = "gpt-image-1";
    /**
     * 面向成本和延迟优化的 GPT Image 1 Mini 模型。
     */
    public static final String GPT_IMAGE_1_MINI = "gpt-image-1-mini";
    /**
     * DALL-E 3 模型。
     */
    public static final String DALL_E_3 = "dall-e-3";
    /**
     * DALL-E 2 模型。
     */
    public static final String DALL_E_2 = "dall-e-2";

    private OpenAIImageModels() {
    }
}

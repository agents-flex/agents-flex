/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

/**
 * 视频生成时输入素材的统一组织方式。
 */
public enum VideoGenerationMode {
    /**
     * 仅使用文本提示词。
     */
    TEXT_TO_VIDEO,
    /**
     * 使用一张图片作为视频首帧。
     */
    FIRST_FRAME,
    /**
     * 同时指定视频首帧和尾帧。
     */
    FIRST_LAST_FRAME,
    /**
     * 使用一张或多张参考图片约束主体、场景或风格。
     */
    REFERENCE_IMAGES,
    /**
     * 使用图片、视频或音频等一种或多种素材进行全能参考生成。
     */
    OMNI_REFERENCE,
    /**
     * 基于源视频执行编辑、重绘或风格转换。
     */
    VIDEO_TO_VIDEO,
    /**
     * 使用音频驱动人物口型、动作或视频节奏。
     */
    AUDIO_DRIVEN
}

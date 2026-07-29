/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

import com.agentsflex.core.util.StringUtil;

import java.util.List;

/**
 * 视频模型基础实现。
 *
 * @param <T> 视频模型配置类型
 */
public abstract class BaseVideoModel<T extends BaseVideoConfig> implements VideoModel {
    /**
     * 当前视频模型实例使用的配置。
     */
    protected final T config;

    protected BaseVideoModel(T config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
    }

    public T getConfig() {
        return config;
    }

    /**
     * 框架统一校验模型能力，再提交到供应商实现。
     */
    @Override
    public final VideoResponse generate(GenerateVideoRequest request) {
        if (request == null) return VideoResponse.error("request must not be null");
        String model = StringUtil.hasText(request.getModel()) ? request.getModel() : config.getModel();
        if (StringUtil.noText(model)) return VideoResponse.error("video model must not be empty");

        VideoResponse error = validateSupportedValue("resolution", request.getResolution(),
            config.getSupportedResolutions(model), model);
        if (error != null) return error;
        error = validateSupportedValue("aspectRatio", request.getAspectRatio(),
            config.getSupportedAspectRatios(model), model);
        if (error != null) return error;
        error = validateSupportedValue("duration", request.getDuration(),
            config.getSupportedDurations(model), model);
        if (error != null) return error;

        VideoGenerationMode inferredMode = inferGenerationMode(request);
        VideoGenerationMode mode = request.getGenerationMode() != null
            ? request.getGenerationMode() : inferredMode;
        error = validateModeInputs(mode, request);
        if (error != null) return error;
        if (request.getGenerationMode() != null && mode != inferredMode &&
            mode != VideoGenerationMode.OMNI_REFERENCE) {
            return VideoResponse.error("generationMode '" + mode +
                "' does not match request media; inferred mode: " + inferredMode);
        }
        error = validateSupportedValue("generationMode", mode,
            config.getSupportedGenerationModes(model), model);
        if (error != null) return error;

        if (Boolean.TRUE.equals(request.getGenerateAudio()) &&
            Boolean.FALSE.equals(config.getSupportAudioGeneration(model))) {
            return VideoResponse.error("generateAudio is not supported by " + model);
        }
        return doGenerate(request);
    }

    /**
     * 供应商适配器实现实际任务提交。
     */
    protected abstract VideoResponse doGenerate(GenerateVideoRequest request);

    /**
     * 根据请求素材推断生成方式。
     */
    protected VideoGenerationMode inferGenerationMode(GenerateVideoRequest request) {
        boolean hasFirstFrame = request.getFirstFrame() != null;
        boolean hasLastFrame = request.getLastFrame() != null;
        boolean hasReferenceImages = request.getReferenceImages() != null &&
            !request.getReferenceImages().isEmpty();
        boolean hasSourceVideo = request.getSourceVideo() != null;
        boolean hasAudio = StringUtil.hasText(request.getAudioUrl());

        if (hasAudio) {
            return hasFirstFrame || hasLastFrame || hasReferenceImages
                ? VideoGenerationMode.OMNI_REFERENCE : VideoGenerationMode.AUDIO_DRIVEN;
        }
        if (hasSourceVideo) {
            return hasFirstFrame || hasLastFrame || hasReferenceImages
                ? VideoGenerationMode.OMNI_REFERENCE : VideoGenerationMode.VIDEO_TO_VIDEO;
        }
        if (hasReferenceImages && (hasFirstFrame || hasLastFrame)) {
            return VideoGenerationMode.OMNI_REFERENCE;
        }
        if (hasLastFrame) return VideoGenerationMode.FIRST_LAST_FRAME;
        if (hasReferenceImages) return VideoGenerationMode.REFERENCE_IMAGES;
        if (hasFirstFrame) return VideoGenerationMode.FIRST_FRAME;
        return VideoGenerationMode.TEXT_TO_VIDEO;
    }

    private VideoResponse validateModeInputs(VideoGenerationMode mode, GenerateVideoRequest request) {
        if (mode == VideoGenerationMode.FIRST_FRAME && request.getFirstFrame() == null) {
            return VideoResponse.error("FIRST_FRAME generation requires firstFrame");
        }
        if (mode == VideoGenerationMode.FIRST_LAST_FRAME &&
            (request.getFirstFrame() == null || request.getLastFrame() == null)) {
            return VideoResponse.error("FIRST_LAST_FRAME generation requires firstFrame and lastFrame");
        }
        if (mode == VideoGenerationMode.VIDEO_TO_VIDEO && request.getSourceVideo() == null) {
            return VideoResponse.error("VIDEO_TO_VIDEO generation requires sourceVideo");
        }
        if (mode == VideoGenerationMode.AUDIO_DRIVEN && StringUtil.noText(request.getAudioUrl())) {
            return VideoResponse.error("AUDIO_DRIVEN generation requires audioUrl");
        }
        if (mode == VideoGenerationMode.OMNI_REFERENCE && !hasReferenceMedia(request)) {
            return VideoResponse.error("OMNI_REFERENCE generation requires reference media");
        }
        if (mode == VideoGenerationMode.REFERENCE_IMAGES &&
            (request.getReferenceImages() == null || request.getReferenceImages().isEmpty())) {
            return VideoResponse.error("REFERENCE_IMAGES generation requires referenceImages");
        }
        return null;
    }

    private boolean hasReferenceMedia(GenerateVideoRequest request) {
        return request.getFirstFrame() != null || request.getLastFrame() != null ||
            request.getSourceVideo() != null || StringUtil.hasText(request.getAudioUrl()) ||
            (request.getReferenceImages() != null && !request.getReferenceImages().isEmpty());
    }

    private <V> VideoResponse validateSupportedValue(String fieldName, V value,
                                                     List<V> supportedValues, String model) {
        if (value == null || supportedValues == null || supportedValues.contains(value)) return null;
        return VideoResponse.error(fieldName + " '" + value + "' is not supported by " + model +
            "; supported values: " + supportedValues);
    }

    /**
     * 使用 Config 中的默认超时时间和轮询间隔提交任务并等待结果。
     *
     * @param request 视频生成请求
     * @return 最终任务响应，可能是成功、失败、取消或等待超时
     * @see BaseVideoConfig#getTimeoutMillis()
     * @see BaseVideoConfig#getPollIntervalMillis()
     */
    public VideoResponse generateAndWait(GenerateVideoRequest request) {
        return generateAndWait(request, config.getTimeoutMillis(), config.getPollIntervalMillis());
    }
}

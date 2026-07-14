/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

import com.agentsflex.core.model.config.BaseModelConfig;

import java.util.List;

/**
 * 视频模型公共配置。
 * <p>
 * 除了继承 {@link BaseModelConfig} 提供的服务商、端点、模型和 API Key 等连接配置外，
 * 还描述当前默认模型支持的视频生成能力、输入限制以及异步任务轮询参数。
 * <p>
 * 能力字段主要用于能力展示、调用前判断和上层 UI 配置，不会自动修改请求载荷。
 * 当调用方通过请求临时切换模型时，应同步确认目标模型的实际能力。
 */
public class BaseVideoConfig extends BaseModelConfig {
    /** 是否支持仅使用文本提示词生成视频。 */
    protected Boolean supportTextToVideo;

    /** 是否支持使用单张图片作为起始画面生成视频。 */
    protected Boolean supportImageToVideo;

    /** 是否支持同时指定首帧和尾帧生成过渡视频。 */
    protected Boolean supportFirstLastFrame;

    /** 是否支持使用一张或多张参考图片约束主体、场景或风格。 */
    protected Boolean supportReferenceImages;

    /** 是否支持输入源视频进行编辑、重绘或风格转换。 */
    protected Boolean supportVideoToVideo;

    /** 是否支持输入音频，用于口型、动作或节奏驱动等场景。 */
    protected Boolean supportAudioInput;

    /** 是否支持在生成视频的同时由模型生成音频。 */
    protected Boolean supportAudioGeneration;

    /** 是否支持负向提示词。 */
    protected Boolean supportNegativePrompt;

    /** 是否支持由服务商自动扩写或优化提示词。 */
    protected Boolean supportPromptExtend;

    /** 是否支持固定摄像机选项。 */
    protected Boolean supportCameraFixed;

    /** 是否支持控制生成结果中的水印。 */
    protected Boolean supportWatermark;

    /** 是否支持指定随机种子。 */
    protected Boolean supportSeed;

    /**
     * 模型允许的最大参考图片数量；未知或不限制时为 {@code null}。
     */
    protected Integer maxReferenceImages;

    /**
     * 模型支持的视频时长集合，单位为秒；未知或不限制时为 {@code null}。
     */
    protected List<Integer> supportedDurations;

    /**
     * 模型支持的分辨率档位，例如 {@code 480p}、{@code 720p}、{@code 1080p}。
     */
    protected List<String> supportedResolutions;

    /**
     * 模型支持的宽高比，例如 {@code 16:9}、{@code 9:16}、{@code 1:1}。
     */
    protected List<String> supportedAspectRatios;

    /**
     * 查询异步任务的路径模板，其中 {@code {taskId}} 会被实际任务 ID 替换。
     * 例如 {@code /api/v1/tasks/{taskId}}。
     */
    protected String queryPath;

    /**
     * 阻塞等待视频结果时的默认轮询间隔，单位为毫秒，默认 10 秒。
     */
    protected long pollIntervalMillis = 10_000L;

    /**
     * 阻塞等待视频结果时的默认最长等待时间，单位为毫秒，默认 10 分钟。
     */
    protected long timeoutMillis = 10 * 60_000L;

    public Boolean getSupportTextToVideo() { return supportTextToVideo; }
    public void setSupportTextToVideo(Boolean supportTextToVideo) { this.supportTextToVideo = supportTextToVideo; }
    public boolean isSupportTextToVideo() { return supportTextToVideo != null && supportTextToVideo; }
    public Boolean getSupportImageToVideo() { return supportImageToVideo; }
    public void setSupportImageToVideo(Boolean supportImageToVideo) { this.supportImageToVideo = supportImageToVideo; }
    public boolean isSupportImageToVideo() { return supportImageToVideo != null && supportImageToVideo; }
    public Boolean getSupportFirstLastFrame() { return supportFirstLastFrame; }
    public void setSupportFirstLastFrame(Boolean supportFirstLastFrame) { this.supportFirstLastFrame = supportFirstLastFrame; }
    public boolean isSupportFirstLastFrame() { return supportFirstLastFrame != null && supportFirstLastFrame; }
    public Boolean getSupportReferenceImages() { return supportReferenceImages; }
    public void setSupportReferenceImages(Boolean supportReferenceImages) { this.supportReferenceImages = supportReferenceImages; }
    public boolean isSupportReferenceImages() { return supportReferenceImages != null && supportReferenceImages; }
    public Boolean getSupportVideoToVideo() { return supportVideoToVideo; }
    public void setSupportVideoToVideo(Boolean supportVideoToVideo) { this.supportVideoToVideo = supportVideoToVideo; }
    public boolean isSupportVideoToVideo() { return supportVideoToVideo != null && supportVideoToVideo; }
    public Boolean getSupportAudioInput() { return supportAudioInput; }
    public void setSupportAudioInput(Boolean supportAudioInput) { this.supportAudioInput = supportAudioInput; }
    public boolean isSupportAudioInput() { return supportAudioInput != null && supportAudioInput; }
    public Boolean getSupportAudioGeneration() { return supportAudioGeneration; }
    public void setSupportAudioGeneration(Boolean supportAudioGeneration) { this.supportAudioGeneration = supportAudioGeneration; }
    public boolean isSupportAudioGeneration() { return supportAudioGeneration != null && supportAudioGeneration; }
    public Boolean getSupportNegativePrompt() { return supportNegativePrompt; }
    public void setSupportNegativePrompt(Boolean supportNegativePrompt) { this.supportNegativePrompt = supportNegativePrompt; }
    public boolean isSupportNegativePrompt() { return supportNegativePrompt != null && supportNegativePrompt; }
    public Boolean getSupportPromptExtend() { return supportPromptExtend; }
    public void setSupportPromptExtend(Boolean supportPromptExtend) { this.supportPromptExtend = supportPromptExtend; }
    public boolean isSupportPromptExtend() { return supportPromptExtend != null && supportPromptExtend; }
    public Boolean getSupportCameraFixed() { return supportCameraFixed; }
    public void setSupportCameraFixed(Boolean supportCameraFixed) { this.supportCameraFixed = supportCameraFixed; }
    public boolean isSupportCameraFixed() { return supportCameraFixed != null && supportCameraFixed; }
    public Boolean getSupportWatermark() { return supportWatermark; }
    public void setSupportWatermark(Boolean supportWatermark) { this.supportWatermark = supportWatermark; }
    public boolean isSupportWatermark() { return supportWatermark != null && supportWatermark; }
    public Boolean getSupportSeed() { return supportSeed; }
    public void setSupportSeed(Boolean supportSeed) { this.supportSeed = supportSeed; }
    public boolean isSupportSeed() { return supportSeed != null && supportSeed; }

    public Integer getMaxReferenceImages() { return maxReferenceImages; }
    public void setMaxReferenceImages(Integer maxReferenceImages) { this.maxReferenceImages = maxReferenceImages; }
    public List<Integer> getSupportedDurations() { return supportedDurations; }
    public void setSupportedDurations(List<Integer> supportedDurations) { this.supportedDurations = supportedDurations; }
    public List<String> getSupportedResolutions() { return supportedResolutions; }
    public void setSupportedResolutions(List<String> supportedResolutions) { this.supportedResolutions = supportedResolutions; }
    public List<String> getSupportedAspectRatios() { return supportedAspectRatios; }
    public void setSupportedAspectRatios(List<String> supportedAspectRatios) { this.supportedAspectRatios = supportedAspectRatios; }

    public String getQueryPath() { return queryPath; }
    public void setQueryPath(String queryPath) {
        this.queryPath = queryPath != null && !queryPath.startsWith("/") ? "/" + queryPath : queryPath;
    }
    /**
     * 根据服务端地址、查询路径模板和任务 ID 构造完整查询地址。
     *
     * @param taskId 服务商返回的任务 ID
     * @return 完整任务查询地址
     */
    public String getQueryUrl(String taskId) {
        return getEndpoint() + queryPath.replace("{taskId}", taskId);
    }
    public long getPollIntervalMillis() { return pollIntervalMillis; }
    public void setPollIntervalMillis(long pollIntervalMillis) { this.pollIntervalMillis = pollIntervalMillis; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }

    @Override
    public String toString() {
        return "BaseVideoConfig{" +
            "provider='" + provider + '\'' +
            ", endpoint='" + endpoint + '\'' +
            ", requestPath='" + requestPath + '\'' +
            ", queryPath='" + queryPath + '\'' +
            ", model='" + model + '\'' +
            ", apiKey='[REDACTED]'" +
            ", supportTextToVideo=" + supportTextToVideo +
            ", supportImageToVideo=" + supportImageToVideo +
            ", supportFirstLastFrame=" + supportFirstLastFrame +
            ", supportReferenceImages=" + supportReferenceImages +
            ", supportVideoToVideo=" + supportVideoToVideo +
            ", supportAudioInput=" + supportAudioInput +
            ", supportAudioGeneration=" + supportAudioGeneration +
            ", pollIntervalMillis=" + pollIntervalMillis +
            ", timeoutMillis=" + timeoutMillis +
            '}';
    }
}

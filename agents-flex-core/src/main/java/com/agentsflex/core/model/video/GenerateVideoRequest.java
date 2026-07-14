/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

import com.agentsflex.core.model.image.Image;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频生成请求。
 * <p>
 * 该请求统一描述文生视频、图生视频、首尾帧生视频、参考图生视频以及视频编辑等场景。
 * 不同服务商和模型支持的字段可能不同，调用前可通过 {@link BaseVideoConfig} 查询默认模型能力；
 * 模型特有且未被统一抽象的参数可通过 {@link #addOption(String, Object)} 传递。
 */
public class GenerateVideoRequest extends BaseVideoRequest {
    /**
     * 正向提示词，用于描述希望生成的视频内容、镜头、运动、风格和光照等信息。
     */
    private String prompt;

    /**
     * 反向提示词，用于描述需要规避的内容或质量问题，例如模糊、抖动和畸变。
     * 仅在模型支持负向提示词时生效。
     */
    private String negativePrompt;

    /**
     * 首帧或单张图生视频的输入图片。
     * <p>
     * 只设置该字段时通常表示普通图生视频；同时设置 {@link #lastFrame} 时表示首尾帧生视频。
     */
    private Image firstFrame;

    /**
     * 尾帧图片，与 {@link #firstFrame} 配合约束视频的起始和结束画面。
     */
    private Image lastFrame;

    /**
     * 参考图片列表，用于约束人物、物体、场景或视觉风格的一致性。
     * 可用数量由具体模型决定。
     */
    private List<Image> referenceImages;

    /**
     * 源视频，用于视频生视频、视频编辑或风格迁移等场景。
     */
    private Video sourceVideo;

    /**
     * 输入音频的远程地址，用于音频驱动视频或音画同步场景。
     */
    private String audioUrl;

    /**
     * 期望生成的视频时长，单位为秒。
     */
    private Integer duration;

    /**
     * 期望输出宽度，单位为像素。通常与 {@link #height} 一起设置。
     */
    private Integer width;

    /**
     * 期望输出高度，单位为像素。通常与 {@link #width} 一起设置。
     */
    private Integer height;

    /**
     * 分辨率档位，例如 {@code 480p}、{@code 720p}、{@code 1080p}。
     * 部分服务商使用该字段，另一些服务商使用具体宽高。
     */
    private String resolution;

    /**
     * 视频宽高比，例如 {@code 16:9}、{@code 9:16}、{@code 1:1} 或 {@code adaptive}。
     */
    private String aspectRatio;

    /**
     * 期望帧率，单位为帧每秒（FPS）。模型不支持自定义帧率时会忽略该字段。
     */
    private Integer fps;

    /**
     * 随机种子。相同请求参数和种子有助于提高结果的可复现性，但不保证完全一致。
     */
    private Integer seed;

    /**
     * 是否在生成结果中添加服务商水印。{@code null} 表示使用服务商默认行为。
     */
    private Boolean watermark;

    /**
     * 是否启用服务商的提示词智能扩写。{@code null} 表示使用服务商默认行为。
     */
    private Boolean promptExtend;

    /**
     * 是否同时生成视频音频。仅对支持原生音频生成的模型有效。
     */
    private Boolean generateAudio;

    /**
     * 是否固定摄像机，减少推拉、摇移等镜头运动。仅在模型支持时生效。
     */
    private Boolean cameraFixed;

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    public Image getFirstFrame() { return firstFrame; }
    public void setFirstFrame(Image firstFrame) { this.firstFrame = firstFrame; }
    public Image getLastFrame() { return lastFrame; }
    public void setLastFrame(Image lastFrame) { this.lastFrame = lastFrame; }
    public List<Image> getReferenceImages() { return referenceImages; }
    public void setReferenceImages(List<Image> referenceImages) { this.referenceImages = referenceImages; }
    /**
     * 添加一张参考图片。
     *
     * @param image 参考图片
     */
    public void addReferenceImage(Image image) {
        if (referenceImages == null) referenceImages = new ArrayList<>();
        referenceImages.add(image);
    }
    public Video getSourceVideo() { return sourceVideo; }
    public void setSourceVideo(Video sourceVideo) { this.sourceVideo = sourceVideo; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    /**
     * 同时设置期望输出的视频宽度和高度。
     *
     * @param width 宽度，单位为像素
     * @param height 高度，单位为像素
     */
    public void setSize(Integer width, Integer height) { this.width = width; this.height = height; }
    /**
     * 获取由宽高组成的尺寸字符串，格式为 {@code 宽*高}，例如 {@code 1280*720}。
     * 宽度或高度未设置时返回 {@code null}。
     *
     * @return 服务商常用的尺寸字符串
     */
    public String getSizeString() { return width != null && height != null ? width + "*" + height : null; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(String aspectRatio) { this.aspectRatio = aspectRatio; }
    public Integer getFps() { return fps; }
    public void setFps(Integer fps) { this.fps = fps; }
    public Integer getSeed() { return seed; }
    public void setSeed(Integer seed) { this.seed = seed; }
    public Boolean getWatermark() { return watermark; }
    public void setWatermark(Boolean watermark) { this.watermark = watermark; }
    public Boolean getPromptExtend() { return promptExtend; }
    public void setPromptExtend(Boolean promptExtend) { this.promptExtend = promptExtend; }
    public Boolean getGenerateAudio() { return generateAudio; }
    public void setGenerateAudio(Boolean generateAudio) { this.generateAudio = generateAudio; }
    public Boolean getCameraFixed() { return cameraFixed; }
    public void setCameraFixed(Boolean cameraFixed) { this.cameraFixed = cameraFixed; }
}

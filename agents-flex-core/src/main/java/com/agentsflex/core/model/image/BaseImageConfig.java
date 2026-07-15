/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.image;

import com.agentsflex.core.model.config.BaseModelConfig;

import java.util.List;

/**
 * 图片模型的公共配置与能力描述。
 * <p>
 * 连接信息、默认模型和密钥继承自 {@link BaseModelConfig}。本类中的 {@link Boolean}
 * 能力字段允许为 {@code null}，表示适配器没有声明该能力；对应的 {@code isSupportXxx()}
 * 方法仅在字段明确为 {@link Boolean#TRUE} 时返回 {@code true}，适合业务层安全判断。
 * </p>
 * <p>这些字段用于能力发现和前置校验，不代表核心层会自动实现或转换相应能力。</p>
 */
public class BaseImageConfig extends BaseModelConfig {
    /** 是否支持仅根据文本提示词生成图片。 */
    protected Boolean supportTextToImage;

    /** 是否支持以输入图片作为条件进行图生图或参考生成。 */
    protected Boolean supportImageToImage;

    /** 是否支持对输入图片执行内容修改、重绘或局部编辑。 */
    protected Boolean supportImageEditing;

    /** 是否允许一次请求携带多张有顺序的输入图片。 */
    protected Boolean supportMultipleInputImages;

    /** 是否允许一次请求返回多张图片。 */
    protected Boolean supportMultipleOutputImages;

    /** 是否支持通过掩膜指定编辑区域。 */
    protected Boolean supportMask;

    /** 是否支持负向提示词。 */
    protected Boolean supportNegativePrompt;

    /** 是否支持由供应商自动扩写或优化提示词。 */
    protected Boolean supportPromptExtend;

    /** 是否支持控制输出水印。 */
    protected Boolean supportWatermark;

    /** 是否支持设置随机种子。 */
    protected Boolean supportSeed;

    /** 单次请求允许的最大输入图片数；为空表示适配器未声明。 */
    protected Integer maxInputImages;

    /** 单次请求允许的最大输出图片数；为空表示适配器未声明。 */
    protected Integer maxOutputImages;

    /**
     * 适配器声明的分辨率或尺寸档位列表，例如 {@code 1K}、{@code 2K}。
     * <p>列表内容采用供应商原始表示，不保证跨供应商一致。</p>
     */
    protected List<String> supportedResolutions;

    public Boolean getSupportTextToImage() { return supportTextToImage; }
    public void setSupportTextToImage(Boolean value) { supportTextToImage = value; }
    /** @return 仅当明确声明支持文生图时返回 {@code true} */
    public boolean isSupportTextToImage() { return Boolean.TRUE.equals(supportTextToImage); }
    public Boolean getSupportImageToImage() { return supportImageToImage; }
    public void setSupportImageToImage(Boolean value) { supportImageToImage = value; }
    /** @return 仅当明确声明支持图生图时返回 {@code true} */
    public boolean isSupportImageToImage() { return Boolean.TRUE.equals(supportImageToImage); }
    public Boolean getSupportImageEditing() { return supportImageEditing; }
    public void setSupportImageEditing(Boolean value) { supportImageEditing = value; }
    /** @return 仅当明确声明支持图片编辑时返回 {@code true} */
    public boolean isSupportImageEditing() { return Boolean.TRUE.equals(supportImageEditing); }
    public Boolean getSupportMultipleInputImages() { return supportMultipleInputImages; }
    public void setSupportMultipleInputImages(Boolean value) { supportMultipleInputImages = value; }
    /** @return 仅当明确声明支持多图输入时返回 {@code true} */
    public boolean isSupportMultipleInputImages() { return Boolean.TRUE.equals(supportMultipleInputImages); }
    public Boolean getSupportMultipleOutputImages() { return supportMultipleOutputImages; }
    public void setSupportMultipleOutputImages(Boolean value) { supportMultipleOutputImages = value; }
    /** @return 仅当明确声明支持多图输出时返回 {@code true} */
    public boolean isSupportMultipleOutputImages() { return Boolean.TRUE.equals(supportMultipleOutputImages); }
    public Boolean getSupportMask() { return supportMask; }
    public void setSupportMask(Boolean value) { supportMask = value; }
    /** @return 仅当明确声明支持掩膜时返回 {@code true} */
    public boolean isSupportMask() { return Boolean.TRUE.equals(supportMask); }
    public Boolean getSupportNegativePrompt() { return supportNegativePrompt; }
    public void setSupportNegativePrompt(Boolean value) { supportNegativePrompt = value; }
    /** @return 仅当明确声明支持负向提示词时返回 {@code true} */
    public boolean isSupportNegativePrompt() { return Boolean.TRUE.equals(supportNegativePrompt); }
    public Boolean getSupportPromptExtend() { return supportPromptExtend; }
    public void setSupportPromptExtend(Boolean value) { supportPromptExtend = value; }
    /** @return 仅当明确声明支持提示词扩写时返回 {@code true} */
    public boolean isSupportPromptExtend() { return Boolean.TRUE.equals(supportPromptExtend); }
    public Boolean getSupportWatermark() { return supportWatermark; }
    public void setSupportWatermark(Boolean value) { supportWatermark = value; }
    /** @return 仅当明确声明支持水印控制时返回 {@code true} */
    public boolean isSupportWatermark() { return Boolean.TRUE.equals(supportWatermark); }
    public Boolean getSupportSeed() { return supportSeed; }
    public void setSupportSeed(Boolean value) { supportSeed = value; }
    /** @return 仅当明确声明支持随机种子时返回 {@code true} */
    public boolean isSupportSeed() { return Boolean.TRUE.equals(supportSeed); }
    public Integer getMaxInputImages() { return maxInputImages; }
    public void setMaxInputImages(Integer maxInputImages) { this.maxInputImages = maxInputImages; }
    public Integer getMaxOutputImages() { return maxOutputImages; }
    public void setMaxOutputImages(Integer maxOutputImages) { this.maxOutputImages = maxOutputImages; }
    public List<String> getSupportedResolutions() { return supportedResolutions; }
    public void setSupportedResolutions(List<String> values) { supportedResolutions = values; }
}

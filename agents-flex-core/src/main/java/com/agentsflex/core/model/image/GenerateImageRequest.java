/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.model.image;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一图片生成请求。
 * <p>
 * 文生图、基于参考图生成和图片编辑都通过该请求表达：未设置 {@link #inputImages}
 * 时通常表示文生图；设置输入图片后，具体适配器会按模型能力选择参考生成或编辑协议。
 * 公共层不暴露独立的异步任务或编辑方法。
 */
public class GenerateImageRequest extends BaseImageRequest {
    /** 描述目标画面、内容和构图要求的正向提示词。 */
    private String prompt;

    /** 描述需要排除的内容或视觉特征的负向提示词。 */
    private String negativePrompt;

    /** 供应商定义的质量档位，例如 {@code standard}、{@code hd}。 */
    private String quality;

    /** 供应商定义的风格标识，例如写实、插画或预置风格名称。 */
    private String style;

    /**
     * 随机种子，用于提高相同参数下结果的可复现性。
     * <p>是否完全可复现取决于供应商、模型版本和推理环境。</p>
     */
    private Integer seed;

    /** 是否要求供应商在输出图片中添加水印。 */
    private Boolean watermark;

    /** 是否允许供应商自动扩写或优化提示词。 */
    private Boolean promptExtend;

    /**
     * 供应商定义的分辨率档位，例如 {@code 1K}、{@code 2K}、{@code 4K}。
     * <p>它与宽高像素并非同一概念；当两者同时设置时，优先级由适配器决定。</p>
     */
    private String resolution;

    /**
     * 输出图片的文件编码格式，例如 {@code png}、{@code jpeg} 或 {@code webp}。
     * <p>该字段描述图片内容格式；{@link BaseImageRequest#getResponseFormat()} 描述响应承载方式。</p>
     */
    private String outputFormat;

    /** 是否启用模型的组图、连续生成或序列生成能力。 */
    private Boolean sequentialGeneration;

    /** 序列生成场景允许返回的最大图片数。 */
    private Integer maxImages;

    /**
     * 有序的输入图片列表，可用于图片编辑、多图融合或参考图生成。
     * <p>图片顺序可能具有业务含义，例如第一张作为底图、后续图片作为主体参考。</p>
     */
    private List<Image> inputImages;

    /**
     * 与 {@link #inputImages} 按索引一一对应的编辑区域列表。
     * <p>外层列表的第 N 项描述第 N 张输入图片；空的内层列表表示该图片没有显式框选区域。
     * 支持的框数量、坐标限制和适用模型由供应商适配器校验。</p>
     */
    private List<List<ImageBoundingBox>> boundingBoxes;

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getNegativePrompt() {
        return negativePrompt;
    }

    public void setNegativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    /** @return 有序输入图片列表；未设置时返回 {@code null} */
    public List<Image> getInputImages() { return inputImages; }

    /**
     * 设置有序输入图片列表。
     *
     * @param inputImages 用于参考生成、融合或编辑的图片列表
     */
    public void setInputImages(List<Image> inputImages) { this.inputImages = inputImages; }

    /**
     * 向输入图片列表末尾追加一张图片。
     *
     * @param image 要追加的输入图片
     */
    public void addInputImage(Image image) {
        if (inputImages == null) inputImages = new ArrayList<>();
        inputImages.add(image);
    }

    /**
     * 从输入列表中移除指定图片。
     *
     * @param image 要移除的图片对象
     */
    public void removeInputImage(Image image) { if (inputImages != null) inputImages.remove(image); }

    /** @return 与输入图片按索引对齐的框选区域列表 */
    public List<List<ImageBoundingBox>> getBoundingBoxes() { return boundingBoxes; }

    /**
     * 设置所有输入图片对应的框选区域。
     *
     * @param boundingBoxes 外层列表应与输入图片列表按索引对齐
     */
    public void setBoundingBoxes(List<List<ImageBoundingBox>> boundingBoxes) { this.boundingBoxes = boundingBoxes; }

    /**
     * 为下一张输入图片追加一组框选区域。
     * <p>调用顺序应与添加输入图片的顺序保持一致。</p>
     *
     * @param imageBoundingBoxes 单张输入图片对应的框选区域列表
     */
    public void addBoundingBoxes(List<ImageBoundingBox> imageBoundingBoxes) {
        if (boundingBoxes == null) boundingBoxes = new ArrayList<>();
        boundingBoxes.add(imageBoundingBoxes);
    }
    public Integer getSeed() { return seed; }
    public void setSeed(Integer seed) { this.seed = seed; }
    public Boolean getWatermark() { return watermark; }
    public void setWatermark(Boolean watermark) { this.watermark = watermark; }
    public Boolean getPromptExtend() { return promptExtend; }
    public void setPromptExtend(Boolean promptExtend) { this.promptExtend = promptExtend; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    public Boolean getSequentialGeneration() { return sequentialGeneration; }
    public void setSequentialGeneration(Boolean sequentialGeneration) { this.sequentialGeneration = sequentialGeneration; }
    public Integer getMaxImages() { return maxImages; }
    public void setMaxImages(Integer maxImages) { this.maxImages = maxImages; }
}

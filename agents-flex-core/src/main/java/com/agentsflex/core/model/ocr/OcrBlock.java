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
package com.agentsflex.core.model.ocr;

import com.agentsflex.core.util.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR 页面中一个具有内容、类型和空间位置的结构化内容块。
 */
public class OcrBlock extends Metadata {
    /**
     * 当前页面内的块编号。
     */
    private int index;
    /**
     * 跨供应商统一类型。
     */
    private OcrBlockType type = OcrBlockType.OTHER;
    /**
     * 供应商原始类型，尤其用于保留无法标准化的新类型。
     */
    private String providerType;
    /**
     * 块在所属页面坐标系中的位置。
     */
    private OcrBoundingBox boundingBox;
    /**
     * 块的正文内容。
     */
    private String content;
    /**
     * 内容格式，例如 text、html、markdown 或 latex。
     */
    private String format;
    /**
     * 与当前块关联的图注块编号。
     */
    private List<Integer> captionBlockIndexes;
    /**
     * 图片块或图文块关联的图片地址。
     */
    private String imageUrl;
    /**
     * 标题层级；非标题块或供应商未识别时为空。
     */
    private Integer titleLevel;

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public OcrBlockType getType() {
        return type;
    }

    public void setType(OcrBlockType type) {
        this.type = type == null ? OcrBlockType.OTHER : type;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public OcrBoundingBox getBoundingBox() {
        return boundingBox;
    }

    public void setBoundingBox(OcrBoundingBox boundingBox) {
        this.boundingBox = boundingBox;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public List<Integer> getCaptionBlockIndexes() {
        return captionBlockIndexes == null
            ? Collections.emptyList() : Collections.unmodifiableList(captionBlockIndexes);
    }

    public void setCaptionBlockIndexes(List<Integer> captionBlockIndexes) {
        this.captionBlockIndexes = captionBlockIndexes == null
            ? null : new ArrayList<>(captionBlockIndexes);
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Integer getTitleLevel() {
        return titleLevel;
    }

    public void setTitleLevel(Integer titleLevel) {
        this.titleLevel = titleLevel;
    }
}

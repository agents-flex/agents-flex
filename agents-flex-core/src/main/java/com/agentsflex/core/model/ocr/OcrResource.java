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

/**
 * OCR 任务产生的外部结果资源。
 *
 * <p>部分供应商不直接返回完整文本，而是返回 Markdown、JSON 或压缩包下载地址。
 * {@code type} 是统一后的资源类别，原始供应商字段仍可通过继承的元数据保存。</p>
 */
public class OcrResource extends Metadata {
    /**
     * 资源类别，例如 {@code markdown}、{@code json} 或 {@code archive}。
     */
    private String type;
    /**
     * 资源下载地址。
     */
    private String url;

    /**
     * 供序列化框架创建空对象。
     */
    public OcrResource() {
    }

    /**
     * 创建指定类别和地址的结果资源。
     */
    public OcrResource(String type, String url) {
        this.type = type;
        this.url = url;
    }

    /**
     * 返回统一资源类别。
     */
    public String getType() {
        return type;
    }

    /**
     * 设置统一资源类别。
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * 返回资源下载地址。
     */
    public String getUrl() {
        return url;
    }

    /**
     * 设置资源下载地址。
     */
    public void setUrl(String url) {
        this.url = url;
    }
}

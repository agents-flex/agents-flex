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

import com.agentsflex.core.util.StringUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * 图片请求的公共参数基类。
 * <p>
 * 该类只描述不同供应商普遍存在的参数，不负责校验某个模型是否支持这些参数。
 * 具体模型适配器应根据自身能力决定映射、忽略或拒绝不支持的字段。
 */
public class BaseImageRequest {
    /**
     * 本次请求使用的模型标识。
     * <p>设置后通常优先于模型配置对象中的默认模型；为空时由适配器使用配置中的模型。</p>
     */
    private String model;

    /**
     * 期望生成的图片数量。
     * <p>有效范围由供应商和具体模型决定，核心层不统一限制。</p>
     */
    private Integer n;

    /**
     * API 响应中图片数据的承载格式，例如 {@code url} 或 {@code b64_json}。
     * <p>该字段描述响应传输方式，不等同于 PNG、JPEG 等图片文件格式。</p>
     */
    private String responseFormat;

    /**
     * 调用方提供的最终用户标识，可用于供应商侧的审计、风控或请求追踪。
     * <p>不要在此字段中放置密码、访问令牌等敏感信息。</p>
     */
    private String user;

    /** 期望输出图片的宽度，单位为像素。 */
    private Integer width;

    /** 期望输出图片的高度，单位为像素。 */
    private Integer height;

    /**
     * 供应商接受的原始尺寸字符串，例如 {@code 1024x1024}。
     * <p>设置后 {@link #getSizeString()} 会优先返回该值，而不是根据宽高重新拼接。</p>
     */
    private String sizeString;

    /**
     * 供应商专属扩展参数。
     * <p>键名和数据结构由具体适配器约定，用于承载核心请求尚未抽象的能力。</p>
     */
    private Map<String, Object> options;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getN() {
        return n;
    }

    public void setN(Integer n) {
        this.n = n;
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    /**
     * 同时设置图片宽度和高度。
     *
     * @param width  图片宽度，单位为像素
     * @param height 图片高度，单位为像素
     */
    public void setSize(Integer width, Integer height) {
        this.width = width;
        this.height = height;
    }

    public void setSizeString(String sizeString) {
        this.sizeString = sizeString;
    }

    /**
     * 获取统一尺寸字符串。
     * <p>若显式设置了 {@link #sizeString}，直接返回该值；否则在宽高均不为空时按
     * {@code 宽x高} 拼接；信息不足时返回 {@code null}。</p>
     *
     * @return 可发送给适配器的尺寸字符串，或 {@code null}
     */
    public String getSizeString() {
        if (StringUtil.hasText(sizeString)) {
            return sizeString;
        } else if (this.width != null && this.height != null) {
            return this.width + "x" + this.height;
        }
        return null;
    }


    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    /**
     * 添加或覆盖一个供应商专属参数。
     *
     * @param key   参数名
     * @param value 参数值
     */
    public void addOption(String key, Object value) {
        if (this.options == null) {
            this.options = new HashMap<>();
        }
        this.options.put(key, value);
    }

    /**
     * 获取供应商专属参数。
     *
     * @param key 参数名
     * @return 参数值；参数不存在时返回 {@code null}
     */
    public Object getOption(String key) {
        return this.options == null ? null : this.options.get(key);
    }

    /**
     * 获取供应商专属参数，不存在时返回默认值。
     *
     * @param key          参数名
     * @param defaultValue 默认值
     * @return 已设置的参数值，或 {@code defaultValue}
     */
    public Object getOptionOrDefault(String key, Object defaultValue) {
        return this.options == null ? defaultValue : this.options.getOrDefault(key, defaultValue);
    }

}

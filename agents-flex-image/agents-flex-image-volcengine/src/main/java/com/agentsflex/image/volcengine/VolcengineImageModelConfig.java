/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.volcengine;

import com.agentsflex.core.model.image.BaseImageConfig;

/**
 * 火山引擎方舟 Images API 配置。
 * <p>当前图片生成接口使用 API Key Bearer 鉴权，历史 AccessKey/SecretKey 方法仅用于源码兼容。</p>
 */
public class VolcengineImageModelConfig extends BaseImageConfig {
    /**
     * 历史 SecretKey 配置值。
     * <p>当前实现不会发送该字段，仅为兼容旧调用代码而保留。</p>
     */
    private String secretKey;

    /** 初始化方舟默认端点、请求路径、Seedream 默认模型和已声明的图片能力。 */
    public VolcengineImageModelConfig() {
        setProvider("volcengine");
        setEndpoint("https://ark.cn-beijing.volces.com");
        setRequestPath("/api/v3/images/generations");
        setModel(VolcengineImageModels.SEEDREAM_5_0_LITE);
        setSupportTextToImage(true);
        setSupportImageToImage(true);
        setSupportImageEditing(true);
        setSupportMultipleInputImages(true);
        setSupportMultipleOutputImages(true);
        setSupportWatermark(true);
        setMaxInputImages(10);
    }

    /**
     * 获取 API Key 的历史别名。
     *
     * @return 当前 API Key
     * @deprecated 方舟图片生成现已统一使用 API Key，请调用 {@link #getApiKey()}。
     */
    @Deprecated
    public String getAccessKey() { return getApiKey(); }

    /**
     * 设置 API Key 的历史别名。
     *
     * @param accessKey API Key
     * @deprecated 请调用 {@link #setApiKey(String)}。
     */
    @Deprecated
    public void setAccessKey(String accessKey) { setApiKey(accessKey); }

    /**
     * 获取历史 SecretKey 值。
     *
     * @return 仅保存在本地配置中的 SecretKey
     * @deprecated 仅用于源码兼容，该值不会发送给方舟接口。
     */
    @Deprecated
    public String getSecretKey() { return secretKey; }

    /**
     * 保存历史 SecretKey 值。
     *
     * @param secretKey 历史 SecretKey
     * @deprecated 仅用于源码兼容，该值不会发送给方舟接口。
     */
    @Deprecated
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
}

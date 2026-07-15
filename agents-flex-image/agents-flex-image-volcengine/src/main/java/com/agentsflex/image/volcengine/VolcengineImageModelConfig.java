/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.volcengine;

import com.agentsflex.core.model.image.BaseImageConfig;

/** Configuration for the Volcengine Ark Images API. */
public class VolcengineImageModelConfig extends BaseImageConfig {
    private String secretKey;

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

    /** @deprecated Ark image generation now authenticates with an API key. */
    @Deprecated
    public String getAccessKey() { return getApiKey(); }

    /** @deprecated Use {@link #setApiKey(String)}. */
    @Deprecated
    public void setAccessKey(String accessKey) { setApiKey(accessKey); }

    /** @deprecated Retained only for source compatibility; it is not sent. */
    @Deprecated
    public String getSecretKey() { return secretKey; }

    /** @deprecated Retained only for source compatibility; it is not sent. */
    @Deprecated
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
}

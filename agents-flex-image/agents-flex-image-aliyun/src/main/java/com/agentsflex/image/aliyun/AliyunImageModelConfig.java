/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.aliyun;

import com.agentsflex.core.model.image.BaseImageConfig;

/** Configuration shared by Qwen-Image and Wan image models on Model Studio. */
public class AliyunImageModelConfig extends BaseImageConfig {
    private String asyncRequestPath = "/api/v1/services/aigc/image-generation/generation";
    private String qwenSynthesisPath = "/api/v1/services/aigc/text2image/image-synthesis";
    private String queryPath = "/api/v1/tasks/{taskId}";
    private long pollIntervalMillis = 10_000L;
    private long timeoutMillis = 10 * 60_000L;

    public AliyunImageModelConfig() {
        setProvider("aliyun");
        setEndpoint("https://dashscope.aliyuncs.com");
        setRequestPath("/api/v1/services/aigc/multimodal-generation/generation");
        setAsyncRequestPath("/api/v1/services/aigc/image-generation/generation");
        setModel(AliyunImageModels.WAN_2_7_IMAGE);

        setSupportTextToImage(true);
        setSupportImageToImage(true);
        setSupportImageEditing(true);
        setSupportMultipleInputImages(true);
        setSupportMultipleOutputImages(true);
        setSupportNegativePrompt(true);
        setSupportPromptExtend(true);
        setSupportWatermark(true);
        setSupportSeed(true);
        setMaxInputImages(9);
        setMaxOutputImages(6);
    }

    public String getAsyncRequestPath() { return asyncRequestPath; }
    public void setAsyncRequestPath(String path) {
        if (path != null && !path.startsWith("/")) path = "/" + path;
        asyncRequestPath = path;
    }
    public String getAsyncUrl() {
        return (getEndpoint() == null ? "" : getEndpoint()) +
            (asyncRequestPath == null ? "" : asyncRequestPath);
    }

    public String getQwenSynthesisPath() { return qwenSynthesisPath; }
    public void setQwenSynthesisPath(String path) {
        if (path != null && !path.startsWith("/")) path = "/" + path;
        qwenSynthesisPath = path;
    }
    public String getQwenSynthesisUrl() {
        return (getEndpoint() == null ? "" : getEndpoint()) +
            (qwenSynthesisPath == null ? "" : qwenSynthesisPath);
    }

    public String getQueryPath() { return queryPath; }
    public void setQueryPath(String path) {
        if (path != null && !path.startsWith("/")) path = "/" + path;
        queryPath = path;
    }
    public String getQueryUrl(String taskId) {
        String path = queryPath == null ? "" : queryPath.replace("{taskId}", taskId);
        return (getEndpoint() == null ? "" : getEndpoint()) + path;
    }
    public long getPollIntervalMillis() { return pollIntervalMillis; }
    public void setPollIntervalMillis(long value) { pollIntervalMillis = value; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long value) { timeoutMillis = value; }

    /** @deprecated Use {@link #getFullUrl()}. */
    @Deprecated
    public String getUrl() { return getFullUrl(); }
}

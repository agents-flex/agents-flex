/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.image.aliyun;

import com.agentsflex.core.model.image.BaseImageConfig;

/**
 * 阿里云百炼图片模型配置。
 * <p>默认使用同步多模态生成接口，同时保留旧千问图片合成和旧万相异步任务接口的路径配置。</p>
 */
public class AliyunImageModelConfig extends BaseImageConfig {
    /** 旧万相图片模型的异步任务提交路径。 */
    private String asyncRequestPath = "/api/v1/services/aigc/image-generation/generation";
    /** 旧千问图片模型的异步图片合成路径。 */
    private String qwenSynthesisPath = "/api/v1/services/aigc/text2image/image-synthesis";
    /** 异步任务查询路径模板，其中 {@code {taskId}} 会替换为真实任务 ID。 */
    private String queryPath = "/api/v1/tasks/{taskId}";
    /** 适配器内部查询异步任务的间隔，单位为毫秒。 */
    private long pollIntervalMillis = 10_000L;
    /** 单次图片生成允许等待异步任务的最长时间，单位为毫秒。 */
    private long timeoutMillis = 10 * 60_000L;

    /** 初始化百炼默认端点、默认模型和已声明的图片能力。 */
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
    /** 设置旧万相异步提交路径；缺少前导斜杠时会自动补齐。 */
    public void setAsyncRequestPath(String path) {
        if (path != null && !path.startsWith("/")) path = "/" + path;
        asyncRequestPath = path;
    }
    /** @return 由 endpoint 与异步提交路径拼接出的完整地址 */
    public String getAsyncUrl() {
        return (getEndpoint() == null ? "" : getEndpoint()) +
            (asyncRequestPath == null ? "" : asyncRequestPath);
    }

    public String getQwenSynthesisPath() { return qwenSynthesisPath; }
    /** 设置旧千问图片合成路径；缺少前导斜杠时会自动补齐。 */
    public void setQwenSynthesisPath(String path) {
        if (path != null && !path.startsWith("/")) path = "/" + path;
        qwenSynthesisPath = path;
    }
    /** @return 由 endpoint 与千问图片合成路径拼接出的完整地址 */
    public String getQwenSynthesisUrl() {
        return (getEndpoint() == null ? "" : getEndpoint()) +
            (qwenSynthesisPath == null ? "" : qwenSynthesisPath);
    }

    public String getQueryPath() { return queryPath; }
    /** 设置任务查询路径模板；缺少前导斜杠时会自动补齐。 */
    public void setQueryPath(String path) {
        if (path != null && !path.startsWith("/")) path = "/" + path;
        queryPath = path;
    }
    /**
     * 生成指定任务的查询地址。
     *
     * @param taskId 百炼返回的任务 ID
     * @return 已替换路径模板的完整查询地址
     */
    public String getQueryUrl(String taskId) {
        String path = queryPath == null ? "" : queryPath.replace("{taskId}", taskId);
        return (getEndpoint() == null ? "" : getEndpoint()) + path;
    }
    public long getPollIntervalMillis() { return pollIntervalMillis; }
    public void setPollIntervalMillis(long value) { pollIntervalMillis = value; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long value) { timeoutMillis = value; }

    /**
     * 获取默认同步接口地址。
     *
     * @return endpoint 与 requestPath 拼接后的完整地址
     * @deprecated 请使用 {@link #getFullUrl()}。
     */
    @Deprecated
    public String getUrl() { return getFullUrl(); }
}

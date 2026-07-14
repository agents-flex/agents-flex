/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

/**
 * 视频模型基础实现。
 *
 * @param <T> 视频模型配置类型
 */
public abstract class BaseVideoModel<T extends BaseVideoConfig> implements VideoModel {
    /** 当前视频模型实例使用的配置。 */
    protected final T config;

    protected BaseVideoModel(T config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
    }

    public T getConfig() { return config; }

    /**
     * 使用 Config 中的默认超时时间和轮询间隔提交任务并等待结果。
     *
     * @param request 视频生成请求
     * @return 最终任务响应，可能是成功、失败、取消或等待超时
     * @see BaseVideoConfig#getTimeoutMillis()
     * @see BaseVideoConfig#getPollIntervalMillis()
     */
    public VideoResponse generateAndWait(GenerateVideoRequest request) {
        return generateAndWait(request, config.getTimeoutMillis(), config.getPollIntervalMillis());
    }
}

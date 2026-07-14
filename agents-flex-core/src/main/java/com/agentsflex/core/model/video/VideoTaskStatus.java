/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

/**
 * 统一的视频异步任务状态。
 * <p>
 * 各服务商的原始状态名称会由适配器转换为该枚举。
 */
public enum VideoTaskStatus {
    /** 请求已提交，但服务商尚未返回更具体的排队状态。 */
    SUBMITTED,
    /** 任务已经进入服务商队列，正在等待执行。 */
    QUEUED,
    /** 服务商正在生成或处理视频。 */
    RUNNING,
    /** 视频生成成功，响应中通常已经包含视频资源。 */
    SUCCEEDED,
    /** 视频任务执行失败。 */
    FAILED,
    /** 视频任务被调用方或服务商取消。 */
    CANCELED,
    /** 本地阻塞等待达到超时上限；服务商任务本身可能仍在继续。 */
    TIMED_OUT,
    /** 无法识别、尚未提供或无法映射的任务状态。 */
    UNKNOWN;

    /**
     * 判断当前状态是否为终态。
     *
     * @return 成功、失败、取消或本地等待超时时返回 {@code true}
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED || this == TIMED_OUT;
    }
}

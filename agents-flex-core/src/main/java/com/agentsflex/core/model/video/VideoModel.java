/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

/**
 * 视频生成模型统一接口。
 * <p>
 * 视频生成通常采用异步任务模式。调用 {@link #generate(GenerateVideoRequest)} 提交任务后，
 * 可通过 {@link #getResult(String)} 自行查询，也可以使用
 * {@link #generateAndWait(GenerateVideoRequest, long, long)} 阻塞等待终态。
 */
public interface VideoModel {
    /**
     * 提交视频生成任务。
     * <p>
     * 返回成功仅表示服务商接受了任务，不表示视频已经生成完成。异步服务通常返回任务 ID
     * 和 {@link VideoTaskStatus#SUBMITTED} 或 {@link VideoTaskStatus#QUEUED} 状态。
     *
     * @param request 视频生成请求
     * @return 任务提交响应；参数或服务商调用失败时返回错误响应
     */
    VideoResponse generate(GenerateVideoRequest request);

    /**
     * 查询视频任务的最新状态和结果。
     * <p>
     * 任务成功后响应中包含视频资源；任务尚未完成时通常只包含任务 ID 和状态。
     *
     * @param taskId 服务商返回的任务 ID
     * @return 最新任务响应
     */
    VideoResponse getResult(String taskId);

    /**
     * 提交视频生成任务，并按固定间隔轮询直到任务进入终态或本地等待超时。
     * <p>
     * 该方法会阻塞当前线程，不适合直接运行在事件循环或要求低延迟的请求线程中。
     * 调用线程被中断时会恢复中断标记，并返回错误响应。达到本地等待上限时返回
     * {@link VideoTaskStatus#TIMED_OUT}，这不代表服务商任务已经失败或取消。
     *
     * @param request 视频生成请求
     * @param timeoutMillis 最长等待时间，单位为毫秒，必须大于 0
     * @param pollIntervalMillis 查询间隔，单位为毫秒，必须大于 0
     * @return 最终任务响应或本地等待超时响应
     * @throws IllegalArgumentException 超时时间或轮询间隔不大于 0
     */
    default VideoResponse generateAndWait(GenerateVideoRequest request, long timeoutMillis, long pollIntervalMillis) {
        VideoResponse response = generate(request);
        if (response == null || response.isError() || response.isTerminal()) {
            return response;
        }
        if (response.getTaskId() == null || response.getTaskId().trim().isEmpty()) {
            return VideoResponse.error("Video provider did not return a task id");
        }
        if (timeoutMillis <= 0 || pollIntervalMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis and pollIntervalMillis must be greater than 0");
        }

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.min(pollIntervalMillis, Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return VideoResponse.error("Interrupted while waiting for video task " + response.getTaskId());
            }
            response = getResult(response.getTaskId());
            if (response == null || response.isTerminal()) {
                return response;
            }
        }
        VideoResponse timeout = VideoResponse.error("Timed out waiting for video task " + response.getTaskId());
        timeout.setTaskId(response.getTaskId());
        timeout.setStatus(VideoTaskStatus.TIMED_OUT);
        return timeout;
    }
}

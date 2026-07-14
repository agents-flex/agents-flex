/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.video;

import com.agentsflex.core.util.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 视频生成任务响应。
 * <p>
 * 视频生成通常是异步任务：首次提交主要返回 {@link #taskId} 和任务状态，任务成功后查询响应
 * 才会包含 {@link #videos}。服务商的原始响应、用量等附加信息可通过继承的元数据 API 获取。
 */
public class VideoResponse extends Metadata {
    /**
     * 服务商返回的视频任务唯一标识，用于后续查询任务状态和结果。
     */
    private String taskId;

    /**
     * 归一化后的任务状态，默认是 {@link VideoTaskStatus#UNKNOWN}。
     */
    private VideoTaskStatus status = VideoTaskStatus.UNKNOWN;

    /**
     * 生成的视频列表。多数模型只返回一个视频，也允许服务商返回多个候选结果。
     */
    private List<Video> videos;

    /**
     * 是否发生错误。该字段用于快速判断提交、查询或等待过程中是否失败。
     */
    private boolean error;

    /**
     * 服务商错误码或框架错误码，未提供时为 {@code null}。
     */
    private String errorCode;

    /**
     * 面向开发者的错误描述，未发生错误时为 {@code null}。
     */
    private String errorMessage;

    /**
     * 创建不带错误码的失败响应。
     *
     * @param errorMessage 错误描述
     * @return 状态为 {@link VideoTaskStatus#FAILED} 的响应
     */
    public static VideoResponse error(String errorMessage) {
        return error(null, errorMessage);
    }

    /**
     * 创建包含错误码的失败响应。
     *
     * @param errorCode 错误码
     * @param errorMessage 错误描述
     * @return 状态为 {@link VideoTaskStatus#FAILED} 的响应
     */
    public static VideoResponse error(String errorCode, String errorMessage) {
        VideoResponse response = new VideoResponse();
        response.setStatus(VideoTaskStatus.FAILED);
        response.setError(true);
        response.setErrorCode(errorCode);
        response.setErrorMessage(errorMessage);
        return response;
    }

    public List<Video> getVideos() { return videos == null ? Collections.emptyList() : Collections.unmodifiableList(videos); }
    /**
     * 获取第一个视频结果，是单结果模型的便利方法。
     *
     * @return 第一个视频；尚未生成结果时返回 {@code null}
     */
    public Video getVideo() { return videos == null || videos.isEmpty() ? null : videos.get(0); }
    public void setVideos(List<Video> videos) { this.videos = videos; }
    /**
     * 添加一个远程视频结果。
     *
     * @param url 视频地址
     */
    public void addVideo(String url) { addVideo(Video.ofUrl(url)); }
    /**
     * 添加一个视频结果。
     *
     * @param video 视频资源
     */
    public void addVideo(Video video) {
        if (videos == null) videos = new ArrayList<>();
        videos.add(video);
    }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public VideoTaskStatus getStatus() { return status; }
    public void setStatus(VideoTaskStatus status) { this.status = status == null ? VideoTaskStatus.UNKNOWN : status; }
    /**
     * 判断任务是否已经进入不会继续变化的终态。
     *
     * @return 成功、失败、取消或等待超时时返回 {@code true}
     */
    public boolean isTerminal() { return status.isTerminal(); }
    public boolean isError() { return error; }
    public void setError(boolean error) { this.error = error; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @Override
    public String toString() {
        return "VideoResponse{" +
            "taskId='" + taskId + '\'' +
            ", status=" + status +
            ", videos=" + videos +
            ", error=" + error +
            ", errorCode='" + errorCode + '\'' +
            ", errorMessage='" + errorMessage + '\'' +
            '}';
    }
}

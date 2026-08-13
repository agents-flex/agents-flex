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
package com.agentsflex.asynctask.handler;

import com.agentsflex.asynctask.*;


import com.agentsflex.core.model.video.GenerateVideoRequest;
import com.agentsflex.core.model.video.VideoModel;
import com.agentsflex.core.model.video.VideoResponse;
import com.agentsflex.core.model.video.VideoTaskStatus;

/**
 * 将通用视频模型的生成与任务查询接口适配为 AsyncTaskHandler。
 *
 * <p>生成请求作为 submit 参数，供应商 taskId 被保存为查询参数，成功时完整 VideoResponse
 * 作为任务结果。该适配器只做协议转换，不负责查询循环和退避。</p>
 */
public class VideoAsyncTaskHandler implements AsyncTaskHandler<GenerateVideoRequest> {
    private final String key;
    private final VideoModel model;

    public VideoAsyncTaskHandler(String key, VideoModel model) {
        if (key == null || key.trim().isEmpty() || model == null) {
            throw new IllegalArgumentException("key and model are required");
        }
        this.key = key;
        this.model = model;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public Class<GenerateVideoRequest> getSubmitParamsType() {
        return GenerateVideoRequest.class;
    }

    @Override
    public TaskSubmitResult submit(GenerateVideoRequest request, TaskSubmitContext context) {
        // 空响应无法提供 taskId 或最终结果，因此映射为可诊断的明确失败。
        VideoResponse response = model.generate(request);
        TaskSubmitResult result = new TaskSubmitResult();
        if (response == null) {
            result.setStatus(AsyncTaskStatus.FAILED);
            result.setErrorMessage("Video provider returned no response");
            return result;
        }
        result.setStatus(map(response.getStatus()));
        result.setResult(response.getStatus() == VideoTaskStatus.SUCCEEDED ? response : null);
        result.setErrorCode(response.getErrorCode());
        result.setErrorMessage(response.getErrorMessage());
        if (response.getTaskId() != null) result.setQueryParams(new TaskQueryParams(response.getTaskId()));
        return result;
    }

    @Override
    public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
        VideoResponse response = model.getResult(params.getExternalTaskId());
        TaskQueryResult result = new TaskQueryResult();
        result.setStatus(response == null ? AsyncTaskStatus.FAILED : map(response.getStatus()));
        result.setProviderStatus(response == null ? null : response.getStatus().name());
        result.setResult(response != null && response.getStatus() == VideoTaskStatus.SUCCEEDED ? response : null);
        result.setErrorCode(response == null ? null : response.getErrorCode());
        result.setErrorMessage(response == null ? "Video provider returned no response" : response.getErrorMessage());
        return result;
    }

    protected AsyncTaskStatus map(VideoTaskStatus status) {
        // 对未知中间态保持 RUNNING，供应商新增排队状态时不会导致兼容性中断。
        if (status == null) return AsyncTaskStatus.RUNNING;
        switch (status) {
            case SUBMITTED:
                return AsyncTaskStatus.SUBMITTED;
            case SUCCEEDED:
                return AsyncTaskStatus.SUCCEEDED;
            case FAILED:
                return AsyncTaskStatus.FAILED;
            case CANCELED:
                return AsyncTaskStatus.CANCELED;
            default:
                return AsyncTaskStatus.RUNNING;
        }
    }
}

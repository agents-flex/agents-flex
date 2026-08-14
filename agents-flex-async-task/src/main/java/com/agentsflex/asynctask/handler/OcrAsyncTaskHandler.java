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


import com.agentsflex.core.model.ocr.OcrModel;
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;

/**
 * 将通用 OCR 模型的提交与查询接口适配为 AsyncTaskHandler。
 *
 * <p>默认实现使用 response.taskId 作为 externalTaskId。供应商需要区域、批次、查询 URL 等
 * 额外字段时，可覆写 {@link #createQueryParams(OcrResponse, OcrRequest)} 和
 * {@link #queryModel(TaskQueryParams, TaskQueryContext)}，这些字段会随任务持久化。</p>
 */
public class OcrAsyncTaskHandler implements AsyncTaskHandler<OcrRequest> {
    private final String key;
    private final OcrModel model;

    /**
     * 创建 OCR 适配器。
     *
     * @param key   注册表中的唯一 Handler 键
     * @param model 实际 OCR 模型客户端
     */
    public OcrAsyncTaskHandler(String key, OcrModel model) {
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
    public Class<OcrRequest> getSubmitParamsType() {
        return OcrRequest.class;
    }

    /**
     * 持久化 OCR 任务只接受供应商能够访问的远程 URL。
     *
     * <p>本地 File 只对当前进程有意义，任务被其他 Worker 领取或服务重启后无法保证仍可访问，
     * 因此要求调用方先上传文件，再提交有效期足够长的 URL。</p>
     */
    @Override
    public void validateSubmitParams(OcrRequest request) {
        if (request.getFile() != null) {
            throw new IllegalArgumentException("Persistent OCR tasks do not support local File input. "
                + "Upload the file to storage and submit OcrRequest.ofUrl(url) instead.");
        }
        if (request.getFileUrl() == null || request.getFileUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("Persistent OCR tasks require an accessible file URL. "
                + "Upload the file to storage and submit OcrRequest.ofUrl(url).");
        }
    }

    @Override
    public TaskSubmitResult submit(OcrRequest request, TaskSubmitContext context) {
        // 提交响应先统一映射；只在供应商确实返回 taskId 时构造后续查询参数。
        OcrResponse response = model.recognize(request);
        TaskSubmitResult result = new TaskSubmitResult();
        apply(response, result);
        if (response != null && response.getTaskId() != null) {
            result.setQueryParams(createQueryParams(response, request));
        }
        return result;
    }

    @Override
    public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
        // 空响应视为明确失败，避免 Worker 对无法诊断的空结果无限轮询。
        OcrResponse response = queryModel(params, context);
        TaskQueryResult result = new TaskQueryResult();
        result.setStatus(response == null ? AsyncTaskStatus.FAILED : map(response.getStatus()));
        result.setProviderStatus(response == null ? null : response.getStatus().name());
        result.setResult(response != null && response.getStatus() == OcrTaskStatus.SUCCEEDED ? response : null);
        result.setErrorCode(response == null ? null : response.getErrorCode());
        result.setErrorMessage(response == null ? "OCR provider returned no response" : response.getErrorMessage());
        return result;
    }

    /**
     * 创建需要持久化的供应商查询参数。
     *
     * <p>子类可加入批次 id、查询 URL、区域或项目等字段，但不能把短生命周期连接对象放入其中。</p>
     */
    protected TaskQueryParams createQueryParams(OcrResponse response, OcrRequest request) {
        return new TaskQueryParams(response.getTaskId());
    }

    /**
     * 根据持久化查询参数调用供应商；子类应与 createQueryParams 的字段约定保持兼容。
     */
    protected OcrResponse queryModel(TaskQueryParams params, TaskQueryContext context) {
        return model.getResult(params.getExternalTaskId());
    }

    protected AsyncTaskStatus map(OcrTaskStatus status) {
        // 未知或新增的非终态默认按 RUNNING 处理，避免供应商扩展状态导致任务被提前终止。
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

    private void apply(OcrResponse response, TaskSubmitResult result) {
        if (response == null) {
            result.setStatus(AsyncTaskStatus.FAILED);
            result.setErrorMessage("OCR provider returned no response");
            return;
        }
        result.setStatus(map(response.getStatus()));
        result.setResult(response.getStatus() == OcrTaskStatus.SUCCEEDED ? response : null);
        result.setErrorCode(response.getErrorCode());
        result.setErrorMessage(response.getErrorMessage());
    }
}

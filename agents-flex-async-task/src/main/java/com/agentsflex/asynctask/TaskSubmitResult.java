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
package com.agentsflex.asynctask;

/**
 * 供应商接受任务后返回的标准化结果。
 *
 * <p>异步接受时通常返回 SUBMITTED 和 TaskQueryParams；供应商同步完成时可以直接返回
 * SUCCEEDED 与 result；明确拒绝时返回 FAILED 和错误信息。</p>
 */
public class TaskSubmitResult {
    /**
     * 只能是可查询状态或终态，不能返回 PENDING_SUBMIT/SUBMITTING。
     */
    private AsyncTaskStatus status;
    /**
     * status 可查询时必填，保存供应商任务标识及附加查询参数。
     */
    private TaskQueryParams queryParams;
    /**
     * 供应商同步完成时的最终结果；异步接受时通常为空。
     */
    private Object result;
    /**
     * 供应商错误码，成功时允许为空。
     */
    private String errorCode;
    /**
     * 可供日志和调用方诊断的错误描述。
     */
    private String errorMessage;

    public AsyncTaskStatus getStatus() {
        return status;
    }

    public void setStatus(AsyncTaskStatus status) {
        this.status = status;
    }

    public TaskQueryParams getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(TaskQueryParams queryParams) {
        this.queryParams = queryParams;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}

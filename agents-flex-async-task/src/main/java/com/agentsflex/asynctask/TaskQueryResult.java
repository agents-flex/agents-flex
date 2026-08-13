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
 * 单次供应商查询的标准化结果。
 *
 * <p>RUNNING 表示仍需继续查询；终态携带 result 或错误；nextQueryParams 允许供应商在查询后
 * 轮换游标、区域端点或令牌，并由框架保存供下一轮使用。</p>
 */
public class TaskQueryResult {
    /**
     * 本轮查询后的统一状态，只能是可查询状态或终态。
     */
    private AsyncTaskStatus status;
    /**
     * 可选的下一轮查询参数；为空时沿用任务当前 queryParams。
     */
    private TaskQueryParams nextQueryParams;
    /**
     * 成功完成后的业务结果；非空时写入任务快照。
     */
    private Object result;
    /**
     * 供应商原始状态文本，用于排障和展示，不参与框架状态判断。
     */
    private String providerStatus;
    private String errorCode;
    private String errorMessage;

    public AsyncTaskStatus getStatus() {
        return status;
    }

    public void setStatus(AsyncTaskStatus status) {
        this.status = status;
    }

    public TaskQueryParams getNextQueryParams() {
        return nextQueryParams;
    }

    public void setNextQueryParams(TaskQueryParams nextQueryParams) {
        this.nextQueryParams = nextQueryParams;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public void setProviderStatus(String providerStatus) {
        this.providerStatus = providerStatus;
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

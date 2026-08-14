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

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 一个供应商异步任务的完整持久化快照。
 *
 * <p>该对象同时保存业务状态、调度时间、重试计数、查询参数、结果、乐观锁版本和 Worker 租约。
 * Store 对外应返回副本，调用方修改副本后再通过带 expectedVersion 的 save 提交。</p>
 */
public class AsyncTask implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 框架任务 id，也是默认提交幂等键。
     */
    private String id;
    /**
     * 用于恢复供应商适配器的注册键。
     */
    private String handlerKey;
    /**
     * Worker 创建供应商任务前持久化的提交参数；提交完成后会被清空。
     */
    private Object submitParams;
    /**
     * 限流和暂停所使用的供应商维度。
     */
    private String providerKey;
    /**
     * 供应商账号并发隔离维度。
     */
    private String accountId;
    /**
     * 租户配额隔离维度。
     */
    private String tenantId;
    /**
     * 提交优先级，值越大越先执行。
     */
    private int priority;
    /**
     * 最早允许提交的 Store 时间戳。
     */
    private long scheduledSubmitAt;
    /**
     * 框架统一生命周期状态。
     */
    private AsyncTaskStatus status;
    /**
     * 下一次查询所需的可持久化供应商参数。
     */
    private TaskQueryParams queryParams;
    /**
     * 成功完成后的业务结果。
     */
    private Object result;
    /**
     * 便于诊断的供应商原始状态文本。
     */
    private String providerStatus;
    /**
     * 供应商或框架归一化错误码，成功状态通常为空。
     */
    private String errorCode;
    /**
     * 最近一次提交、查询或框架状态转换产生的错误说明。
     */
    private String errorMessage;
    /**
     * 已执行的供应商查询总次数。
     */
    private int queryCount;
    /**
     * 连续可重试查询错误次数，成功查询后清零。
     */
    private int consecutiveErrors;
    /**
     * 下一次允许查询的 Store 时间戳。
     */
    private long nextQueryAt;
    /**
     * 框架停止跟踪任务的绝对截止时间。
     */
    private long deadlineAt;
    /**
     * 当前租约持有 Worker。
     */
    private String leaseOwner;
    /**
     * 每次领取都重新生成的 fencing token。
     */
    private String leaseId;
    /**
     * 租约失效的 Store 时间戳。
     */
    private long leaseUntil;
    /**
     * CAS 乐观锁版本，创建为 0，领取和保存时递增。
     */
    private long version;
    /**
     * 任务首次创建的 Store 权威时间，创建后不应修改。
     */
    private long createdAt;
    /**
     * 最近一次业务状态更新的 Store 权威时间；租约续期不要求修改。
     */
    private long updatedAt;
    /**
     * 单调取消标记：一旦为 true，后续保存不得覆盖为 false。
     */
    private boolean cancellationRequested;
    /**
     * 由应用透传给 Handler 的扩展信息。
     */
    private Map<String, Object> metadata;

    /**
     * 创建容器字段防御性复制后的任务副本。
     */
    public AsyncTask copy() {
        AsyncTask copy = new AsyncTask();
        copy.id = id;
        copy.handlerKey = handlerKey;
        copy.submitParams = submitParams;
        copy.providerKey = providerKey;
        copy.accountId = accountId;
        copy.tenantId = tenantId;
        copy.priority = priority;
        copy.scheduledSubmitAt = scheduledSubmitAt;
        copy.status = status;
        copy.queryParams = queryParams == null ? null : queryParams.copy();
        copy.result = result;
        copy.providerStatus = providerStatus;
        copy.errorCode = errorCode;
        copy.errorMessage = errorMessage;
        copy.queryCount = queryCount;
        copy.consecutiveErrors = consecutiveErrors;
        copy.nextQueryAt = nextQueryAt;
        copy.deadlineAt = deadlineAt;
        copy.leaseOwner = leaseOwner;
        copy.leaseId = leaseId;
        copy.leaseUntil = leaseUntil;
        copy.version = version;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.cancellationRequested = cancellationRequested;
        copy.setMetadata(metadata);
        return copy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHandlerKey() {
        return handlerKey;
    }

    public void setHandlerKey(String handlerKey) {
        this.handlerKey = handlerKey;
    }

    public Object getSubmitParams() {
        return submitParams;
    }

    public void setSubmitParams(Object submitParams) {
        this.submitParams = submitParams;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = providerKey;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public long getScheduledSubmitAt() {
        return scheduledSubmitAt;
    }

    public void setScheduledSubmitAt(long scheduledSubmitAt) {
        this.scheduledSubmitAt = scheduledSubmitAt;
    }

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

    public int getQueryCount() {
        return queryCount;
    }

    public void setQueryCount(int queryCount) {
        this.queryCount = queryCount;
    }

    public int getConsecutiveErrors() {
        return consecutiveErrors;
    }

    public void setConsecutiveErrors(int consecutiveErrors) {
        this.consecutiveErrors = consecutiveErrors;
    }

    public long getNextQueryAt() {
        return nextQueryAt;
    }

    public void setNextQueryAt(long nextQueryAt) {
        this.nextQueryAt = nextQueryAt;
    }

    public long getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(long deadlineAt) {
        this.deadlineAt = deadlineAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public void setLeaseId(String leaseId) {
        this.leaseId = leaseId;
    }

    public long getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(long leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    public void setCancellationRequested(boolean cancellationRequested) {
        this.cancellationRequested = cancellationRequested;
    }

    public Map<String, Object> getMetadata() {
        return metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? null : new HashMap<>(metadata);
    }
}

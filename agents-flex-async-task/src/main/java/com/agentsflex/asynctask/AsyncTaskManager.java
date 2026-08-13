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

import com.agentsflex.asynctask.handler.*;
import com.agentsflex.asynctask.policy.*;
import com.agentsflex.asynctask.store.AsyncTaskStore;


import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务的应用入口，提供同步提交和持久化排队两种模式。
 *
 * <p>{@code submit} 立即调用供应商，提交参数不会落库；{@code enqueue} 先持久化参数，
 * 后续由 Worker 在配额允许且计划时间到达后提交，适用于优先级、限流和延迟提交。</p>
 */
public final class AsyncTaskManager {
    private final AsyncTaskStore store;
    private final AsyncTaskHandlerRegistry registry;

    /**
     * 创建任务管理器。
     *
     * @param store    任务持久化与权威时钟来源
     * @param registry 根据 handlerKey 获取供应商适配器的注册表
     */
    public AsyncTaskManager(AsyncTaskStore store, AsyncTaskHandlerRegistry registry) {
        if (store == null || registry == null) throw new IllegalArgumentException("store and registry are required");
        this.store = store;
        this.registry = registry;
    }

    /**
     * 立即同步调用供应商创建异步任务，不持久化提交参数。
     *
     * @param handlerKey            供应商 Handler 注册键
     * @param params                Handler 接受的业务提交参数
     * @param trackingTimeoutMillis 从创建时刻起允许自动跟踪的最长时间
     * @return 已保存供应商提交结果的任务快照
     */
    public <P> AsyncTask submit(String handlerKey, P params, long trackingTimeoutMillis) {
        return submit(handlerKey, params, trackingTimeoutMillis, Collections.emptyMap());
    }

    /**
     * 立即同步调用供应商创建异步任务，并保存 metadata。
     *
     * <p>框架会先创建 SUBMITTING 快照再访问外部系统。若调用抛出异常，任务保存为
     * SUBMIT_UNKNOWN，而不是 FAILED，以表达“供应商可能已经接收但响应丢失”。</p>
     *
     * @param handlerKey            供应商 Handler 注册键
     * @param params                Handler 接受的业务提交参数，本方法不会持久化该对象
     * @param trackingTimeoutMillis 自动跟踪总时限，必须大于 0
     * @param metadata              持久化并透传给 Handler 的扩展信息
     * @return 完成提交阶段并经过 CAS 保存的任务快照
     */
    public <P> AsyncTask submit(String handlerKey, P params, long trackingTimeoutMillis,
                                Map<String, Object> metadata) {
        if (trackingTimeoutMillis <= 0)
            throw new IllegalArgumentException("trackingTimeoutMillis must be greater than 0");
        AsyncTaskHandler<?> handler = registry.get(handlerKey);
        if (params == null || !handler.getSubmitParamsType().isInstance(params)) {
            throw new IllegalArgumentException("Handler " + handlerKey + " requires submit params of type "
                + handler.getSubmitParamsType().getName());
        }

        // 先落库 SUBMITTING 快照，确保进程在供应商调用前后崩溃时仍留下可诊断记录。
        long now = store.currentTimeMillis();
        AsyncTask task = new AsyncTask();
        task.setId(UUID.randomUUID().toString());
        task.setHandlerKey(handlerKey);
        task.setStatus(AsyncTaskStatus.SUBMITTING);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeadlineAt(safeAdd(now, trackingTimeoutMillis));
        task.setMetadata(metadata);
        task = store.create(task);

        try {
            // 外部调用只发生一次；Handler 返回值必须转换并校验为框架支持的状态。
            TaskSubmitResult result = submitChecked(handler, params,
                new TaskSubmitContext(task.getId(), now, metadata));
            applySubmitResult(task, result, now);
        } catch (RuntimeException error) {
            // 网络异常无法证明供应商未创建任务，使用 SUBMIT_UNKNOWN 防止盲目重复提交。
            task.setStatus(AsyncTaskStatus.SUBMIT_UNKNOWN);
            task.setErrorMessage(error.getMessage());
            task.setUpdatedAt(store.currentTimeMillis());
        }
        // 使用 create 返回的版本执行 CAS，避免并发取消等更新被本次提交结果静默覆盖。
        return store.save(task, task.getVersion());
    }

    /**
     * 将可序列化的提交参数持久化，等待后台 Worker 在准入控制后执行。
     *
     * @param handlerKey            Handler 注册键
     * @param params                必须可序列化的提交参数，Worker 重启后会从 Store 恢复
     * @param trackingTimeoutMillis 包含排队和查询阶段的总跟踪时限
     * @param options               优先级、延迟和隔离维度；为空时使用默认值
     * @return 状态为 PENDING_SUBMIT 的持久化任务
     */
    public <P extends Serializable> AsyncTask enqueue(String handlerKey, P params,
                                                      long trackingTimeoutMillis,
                                                      AsyncTaskSubmissionOptions options) {
        if (trackingTimeoutMillis <= 0)
            throw new IllegalArgumentException("trackingTimeoutMillis must be greater than 0");
        AsyncTaskHandler<?> handler = registry.get(handlerKey);
        if (params == null || !handler.getSubmitParamsType().isInstance(params)) {
            throw new IllegalArgumentException("Handler " + handlerKey + " requires submit params of type "
                + handler.getSubmitParamsType().getName());
        }
        AsyncTaskSubmissionOptions effective = options == null ? new AsyncTaskSubmissionOptions() : options;
        // enqueue 不访问供应商，只保存提交信息和调度维度，供任意 Worker 恢复执行。
        long now = store.currentTimeMillis();
        AsyncTask task = new AsyncTask();
        task.setId(UUID.randomUUID().toString());
        task.setHandlerKey(handlerKey);
        task.setSubmitParams(params);
        task.setProviderKey(hasText(effective.getProviderKey()) ? effective.getProviderKey() : handlerKey);
        task.setAccountId(effective.getAccountId());
        task.setTenantId(effective.getTenantId());
        task.setPriority(effective.getPriority());
        task.setScheduledSubmitAt(safeAdd(now, effective.getDelayMillis()));
        task.setStatus(AsyncTaskStatus.PENDING_SUBMIT);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setDeadlineAt(safeAdd(now, trackingTimeoutMillis));
        task.setMetadata(effective.getMetadata());
        return store.create(task);
    }

    /**
     * 获取 Store 中的最新任务快照；不存在时返回 null。
     */
    public AsyncTask get(String taskId) {
        return store.load(taskId);
    }

    /**
     * 请求取消任务；返回 false 表示不存在、已终态或已请求过取消。
     */
    public boolean cancel(String taskId) {
        return store.requestCancellation(taskId);
    }

    @SuppressWarnings("unchecked")
    private <P> TaskSubmitResult submitChecked(AsyncTaskHandler<?> handler, P params,
                                               TaskSubmitContext context) {
        return ((AsyncTaskHandler<P>) handler).submit(params, context);
    }

    private void applySubmitResult(AsyncTask task, TaskSubmitResult result, long now) {
        // Handler 只能把任务推进到“可查询”或“终态”，不能返回框架内部提交状态。
        if (result == null || result.getStatus() == null) {
            throw new IllegalStateException("Async task handler returned no submit status");
        }
        if (!result.getStatus().isQueryable() && !result.getStatus().isTerminal()) {
            throw new IllegalStateException("Async task handler returned an unsupported submit status: "
                + result.getStatus());
        }
        if (result.getStatus().isQueryable() && (result.getQueryParams() == null
            || blank(result.getQueryParams().getExternalTaskId()))) {
            throw new IllegalStateException("Async task handler returned no query parameters");
        }
        task.setStatus(result.getStatus());
        task.setQueryParams(result.getQueryParams());
        task.setResult(result.getResult());
        task.setErrorCode(result.getErrorCode());
        task.setErrorMessage(result.getErrorMessage());
        task.setNextQueryAt(now);
        task.setUpdatedAt(store.currentTimeMillis());
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasText(String value) {
        return !blank(value);
    }

    private long safeAdd(long value, long delta) {
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
}

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
import com.agentsflex.asynctask.store.AsyncTaskVersionConflictException;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 后台任务执行器：领取到期提交任务或查询任务，并且每次租约只执行一次外部调用。
 *
 * <p>Store 的 leaseId 用于 fencing 过期 Worker，version 用于 CAS 保存。一次执行失败时，
 * Worker 按重试策略更新下次查询时间；达到终态、取消或超时后停止调度。</p>
 */
public final class AsyncTaskWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AsyncTaskWorker.class);

    private final String workerId;
    private final AsyncTaskStore store;
    private final AsyncTaskHandlerRegistry registry;
    private final AsyncTaskRetryPolicy retryPolicy;
    private final AsyncTaskAdmissionPolicy admissionPolicy;
    private final long leaseMillis;
    private ScheduledExecutorService scheduler;
    private boolean closed;

    /**
     * 创建不限制后台提交准入的 Worker。
     *
     * @param workerId    当前 Worker 实例的稳定唯一标识
     * @param store       任务 Store
     * @param registry    Handler 注册表
     * @param retryPolicy 查询间隔和异常退避策略
     * @param leaseMillis 单次外部调用预留的租约长度
     */
    public AsyncTaskWorker(String workerId, AsyncTaskStore store,
                           AsyncTaskHandlerRegistry registry, AsyncTaskRetryPolicy retryPolicy,
                           long leaseMillis) {
        this(workerId, store, registry, retryPolicy, taskAdmissionAllowAll(), leaseMillis);
    }

    /**
     * 创建带提交准入控制的 Worker。
     *
     * <p>leaseMillis 应覆盖一次供应商调用与结果保存的最长正常耗时；耗时可能超过租约时，
     * 应由调用层在执行期间使用 Store renewLease 主动续租。</p>
     *
     * @param workerId        Worker 实例唯一标识，多实例之间不能重复
     * @param store           支持 CAS 和租约的任务 Store
     * @param registry        Handler 注册表
     * @param retryPolicy     查询重试策略
     * @param admissionPolicy 待提交任务准入策略
     * @param leaseMillis     租约毫秒数，必须大于 0
     */
    public AsyncTaskWorker(String workerId, AsyncTaskStore store,
                           AsyncTaskHandlerRegistry registry, AsyncTaskRetryPolicy retryPolicy,
                           AsyncTaskAdmissionPolicy admissionPolicy, long leaseMillis) {
        if (workerId == null || store == null || registry == null || retryPolicy == null
            || admissionPolicy == null || leaseMillis <= 0) {
            throw new IllegalArgumentException("workerId, store, registry, policies and leaseMillis are required");
        }
        this.workerId = workerId;
        this.store = store;
        this.registry = registry;
        this.retryPolicy = retryPolicy;
        this.admissionPolicy = admissionPolicy;
        this.leaseMillis = leaseMillis;
    }

    /**
     * 领取并提交最多 limit 个通过准入策略的待提交任务，返回实际领取数量。
     *
     * @param limit 本轮最多领取数量，必须大于 0
     * @return 实际获得租约的任务数量；策略拒绝和并发竞争任务不计入
     */
    public int submitDueTasks(int limit) {
        validateRun(limit);
        List<AsyncTask> claimed = store.claimDueSubmissions(
            workerId, store.currentTimeMillis(), leaseMillis, limit, admissionPolicy);
        for (AsyncTask task : claimed) submit(task);
        return claimed.size();
    }

    /**
     * 领取并查询最多 limit 个到期任务，每个任务本轮只访问供应商一次。
     *
     * @param limit 本轮最多领取数量，必须大于 0
     * @return 实际获得租约的任务数量
     */
    public int queryDueTasks(int limit) {
        validateRun(limit);
        List<AsyncTask> claimed = store.claimDueTasks(
            workerId, store.currentTimeMillis(), leaseMillis, limit);
        for (AsyncTask task : claimed) process(task);
        return claimed.size();
    }

    private void submit(AsyncTask task) {
        try {
            // 外部提交前优先处理取消和截止时间，避免为已无业务价值的任务创建远端资源。
            if (task.isCancellationRequested()) {
                task.setStatus(AsyncTaskStatus.CANCELED);
                task.setErrorMessage("Task was canceled before submission");
            } else if (store.currentTimeMillis() >= task.getDeadlineAt()) {
                task.setStatus(AsyncTaskStatus.TRACKING_TIMED_OUT);
                task.setErrorMessage("Task deadline exceeded before submission");
            } else {
                submitProvider(task);
            }
            // 提交期间可能发生并发取消，保存供应商结果前必须再次读取并合并单调取消标记。
            mergeCancellation(task);
            task.setUpdatedAt(store.currentTimeMillis());
            store.save(task, task.getVersion());
        } catch (RuntimeException error) {
            recordSubmitFailure(task, error);
        } finally {
            // 无论外部调用或保存是否成功都释放当前租约；owner/leaseId 不匹配时 Store 会幂等忽略。
            store.releaseLease(task.getId(), workerId, task.getLeaseId());
        }
    }

    private void submitProvider(AsyncTask task) {
        AsyncTaskHandler<?> handler = registry.get(task.getHandlerKey());
        if (task.getSubmitParams() == null || !handler.getSubmitParamsType().isInstance(task.getSubmitParams())) {
            throw new IllegalStateException("Persisted submit parameters do not match handler: " + task.getHandlerKey());
        }
        TaskSubmitResult result = submitChecked(handler, task.getSubmitParams(),
            new TaskSubmitContext(task.getId(), store.currentTimeMillis(), task.getMetadata()));
        validateSubmitResult(result);
        task.setStatus(result.getStatus());
        task.setQueryParams(result.getQueryParams());
        task.setResult(result.getResult());
        task.setErrorCode(result.getErrorCode());
        task.setErrorMessage(result.getErrorMessage());
        // 供应商已收到请求后不再需要提交参数，清空可减少长期存储和敏感数据暴露。
        task.setSubmitParams(null);
        if (result.getStatus().isQueryable()) task.setNextQueryAt(store.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    private <P> TaskSubmitResult submitChecked(AsyncTaskHandler<?> handler, Object params,
                                               TaskSubmitContext context) {
        return ((AsyncTaskHandler<P>) handler).submit((P) params, context);
    }

    private void validateSubmitResult(TaskSubmitResult result) {
        if (result == null || result.getStatus() == null
            || (!result.getStatus().isQueryable() && !result.getStatus().isTerminal())) {
            throw new IllegalStateException("Async task handler returned an invalid submit result");
        }
        if (result.getStatus().isQueryable() && (result.getQueryParams() == null
            || blank(result.getQueryParams().getExternalTaskId()))) {
            throw new IllegalStateException("Async task handler returned no query parameters");
        }
    }

    private void recordSubmitFailure(AsyncTask claimed, RuntimeException error) {
        try {
            // 重新加载是为了合并并发取消/版本变化，并确认租约没有被其他 Worker 接管。
            AsyncTask current = store.load(claimed.getId());
            if (current == null || !sameLease(current, claimed)) return;
            current.setStatus(AsyncTaskStatus.SUBMIT_UNKNOWN);
            current.setErrorMessage(error.getMessage());
            current.setUpdatedAt(store.currentTimeMillis());
            store.save(current, current.getVersion());
        } catch (RuntimeException saveError) {
            log.warn("Failed to record async task submission error, taskId={}", claimed.getId(), saveError);
        }
    }

    private void process(AsyncTask task) {
        try {
            long now = store.currentTimeMillis();
            // 查询供应商之前先处理本地取消和跟踪截止时间，避免无意义的外部请求。
            if (task.isCancellationRequested()) {
                task.setStatus(AsyncTaskStatus.CANCELED);
                task.setErrorMessage("Task tracking was canceled");
            } else if (now >= task.getDeadlineAt()) {
                task.setStatus(AsyncTaskStatus.TRACKING_TIMED_OUT);
                task.setErrorMessage("Task tracking deadline exceeded");
            } else {
                queryProvider(task, now);
            }
            // 查询期间仍可能收到取消请求；最终保存前再次合并可保证取消不会丢失。
            mergeCancellation(task);
            task.setUpdatedAt(store.currentTimeMillis());
            store.save(task, task.getVersion());
        } catch (RuntimeException error) {
            handleQueryError(task, error);
        } finally {
            // 释放只针对本次领取生成的 leaseId，过期 Worker 无法释放新 Worker 的租约。
            store.releaseLease(task.getId(), workerId, task.getLeaseId());
        }
    }

    private void queryProvider(AsyncTask task, long now) {
        AsyncTaskHandler<?> handler = registry.get(task.getHandlerKey());
        TaskQueryContext context = new TaskQueryContext(
            task.getId(), task.getQueryCount(), task.getConsecutiveErrors(),
            task.getCreatedAt(), task.getDeadlineAt(), now, task.getMetadata());
        TaskQueryResult result = handler.query(task.getQueryParams(), context);
        // Handler 结果先完成结构和状态校验，再修改任务，避免半更新快照进入 Store。
        if (result == null || result.getStatus() == null) {
            throw new IllegalStateException("Async task handler returned no query status");
        }
        if (!result.getStatus().isQueryable() && !result.getStatus().isTerminal()) {
            throw new IllegalStateException("Async task handler returned an unsupported query status: "
                + result.getStatus());
        }
        if (result.getStatus().isQueryable() && result.getNextQueryParams() != null
            && blank(result.getNextQueryParams().getExternalTaskId())) {
            throw new IllegalStateException("Async task handler returned invalid next query parameters");
        }
        task.setQueryCount(task.getQueryCount() + 1);
        task.setConsecutiveErrors(0);
        task.setStatus(result.getStatus());
        task.setProviderStatus(result.getProviderStatus());
        task.setErrorCode(result.getErrorCode());
        task.setErrorMessage(result.getErrorMessage());
        if (result.getResult() != null) task.setResult(result.getResult());
        // 供应商可以轮换查询游标或端点；未返回 nextQueryParams 时继续沿用原参数。
        if (result.getNextQueryParams() != null) task.setQueryParams(result.getNextQueryParams());
        if (result.getStatus().isQueryable()) {
            // 下一次调度不得晚于 deadline，确保到期任务能够及时进入超时分支。
            task.setNextQueryAt(Math.min(task.getDeadlineAt(),
                safeAdd(now, retryPolicy.nextQueryDelayMillis(task, result))));
        }
    }

    private void handleQueryError(AsyncTask claimed, RuntimeException error) {
        try {
            // 异常处理从 Store 重新取最新版本，只允许仍持有原租约的 Worker写入退避状态。
            AsyncTask task = store.load(claimed.getId());
            if (task == null || !sameLease(task, claimed)) return;
            task.setConsecutiveErrors(task.getConsecutiveErrors() + 1);
            task.setErrorMessage(error.getMessage());
            long now = store.currentTimeMillis();
            if (now >= task.getDeadlineAt()) {
                task.setStatus(AsyncTaskStatus.TRACKING_TIMED_OUT);
            } else if (retryPolicy.shouldRetry(task, error)) {
                // 错误退避同样受 deadline 上限约束，防止任务越过跟踪时限后长期沉睡。
                task.setNextQueryAt(Math.min(task.getDeadlineAt(),
                    safeAdd(now, retryPolicy.nextErrorDelayMillis(task, error))));
            } else {
                task.setStatus(AsyncTaskStatus.FAILED);
            }
            task.setUpdatedAt(now);
            store.save(task, task.getVersion());
        } catch (RuntimeException saveError) {
            log.warn("Failed to record async task query error, taskId={}", claimed.getId(), saveError);
        }
    }

    private void mergeCancellation(AsyncTask task) {
        AsyncTask current = store.load(task.getId());
        // leaseId 是 fencing token；即使 workerId 相同，旧一轮领取也不能覆盖新租约。
        if (current == null || !sameLease(current, task)) {
            throw new IllegalStateException("Async task lease was lost: " + task.getId());
        }
        if (current.getVersion() != task.getVersion()) {
            // Worker 执行期间允许的并发版本变化只有取消；其他变化视为真实 CAS 冲突。
            if (!current.isCancellationRequested()) {
                throw new AsyncTaskVersionConflictException(task.getId(), task.getVersion(), current.getVersion());
            }
            task.setVersion(current.getVersion());
        }
        if (current.isCancellationRequested()) {
            task.setCancellationRequested(true);
            task.setStatus(AsyncTaskStatus.CANCELED);
            task.setErrorMessage("Task tracking was canceled");
        }
    }

    /**
     * 启动单线程定时扫描；重复调用保持幂等，不会创建第二个调度线程。
     *
     * @param scanIntervalMillis 两轮扫描完成后的固定间隔
     * @param batchSize          每轮提交和查询阶段各自的最大领取数量
     */
    public synchronized void start(long scanIntervalMillis, int batchSize) {
        if (scanIntervalMillis <= 0 || batchSize <= 0)
            throw new IllegalArgumentException("scanIntervalMillis and batchSize must be greater than 0");
        if (closed) throw new IllegalStateException("AsyncTaskWorker is closed");
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "async-task-worker-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                // 同一线程先处理待提交队列，再处理查询队列，避免两个阶段在本 Worker 内并发修改任务。
                submitDueTasks(batchSize);
                queryDueTasks(batchSize);
            } catch (RuntimeException error) {
                log.warn("Async task worker scan failed, workerId={}", workerId, error);
            }
        }, 0, scanIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 返回定时扫描线程是否已启动且尚未关闭。
     */
    public synchronized boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /**
     * 永久关闭 Worker 并中断调度线程；关闭后的实例不能重新启动或手动扫描。
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private boolean sameLease(AsyncTask left, AsyncTask right) {
        return equals(left.getLeaseOwner(), right.getLeaseOwner())
            && equals(left.getLeaseId(), right.getLeaseId());
    }

    private boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void validateRun(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be greater than 0");
        synchronized (this) {
            if (closed) throw new IllegalStateException("AsyncTaskWorker is closed");
        }
    }

    private static AsyncTaskAdmissionPolicy taskAdmissionAllowAll() {
        return (task, allTasks, now) -> true;
    }

    private long safeAdd(long value, long delta) {
        if (delta <= 0) throw new IllegalStateException("Retry policy returned a non-positive delay");
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
}

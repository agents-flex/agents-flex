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
package com.agentsflex.asynctask.store;

import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.AsyncTaskAdmissionPolicy;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用于单机应用、本地开发和测试的进程内异步任务 Store。
 *
 * <p>所有复合操作都在 tasks 监视器内完成，以模拟外部 Store 的原子 CAS 与领取语义。
 * 数据只存在于当前 JVM，重启会丢失，也不能协调多个应用实例。</p>
 */
public final class InMemoryAsyncTaskStore implements AsyncTaskStore {
    private final ConcurrentMap<String, AsyncTask> tasks = new ConcurrentHashMap<>();

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public AsyncTask create(AsyncTask task) {
        requireTask(task);
        synchronized (tasks) {
            if (tasks.containsKey(task.getId()))
                throw new IllegalStateException("Async task already exists: " + task.getId());
            AsyncTask stored = task.copy();
            stored.setVersion(0);
            tasks.put(stored.getId(), stored);
            return stored.copy();
        }
    }

    @Override
    public AsyncTask load(String taskId) {
        AsyncTask task = tasks.get(taskId);
        return task == null ? null : task.copy();
    }

    @Override
    public AsyncTask save(AsyncTask task, long expectedVersion) {
        requireTask(task);
        synchronized (tasks) {
            // CAS、租约校验、取消标记合并和版本递增必须处于同一个临界区。
            AsyncTask current = tasks.get(task.getId());
            if (current == null) throw new IllegalStateException("Async task does not exist: " + task.getId());
            if (current.getVersion() != expectedVersion) {
                throw new AsyncTaskVersionConflictException(task.getId(), expectedVersion, current.getVersion());
            }
            if (current.getLeaseId() != null && current.getLeaseUntil() <= currentTimeMillis()) {
                throw new IllegalStateException("Async task lease has expired: " + task.getId());
            }
            ensureLeaseNotOverwritten(task, current);
            AsyncTask stored = task.copy();
            stored.setCancellationRequested(current.isCancellationRequested() || task.isCancellationRequested());
            stored.setVersion(expectedVersion + 1);
            tasks.put(stored.getId(), stored);
            return stored.copy();
        }
    }

    @Override
    public List<AsyncTask> claimDueSubmissions(String workerId, long now, long leaseMillis, int limit,
                                               AsyncTaskAdmissionPolicy admissionPolicy) {
        validateClaim(workerId, leaseMillis, limit);
        if (admissionPolicy == null) throw new IllegalArgumentException("admissionPolicy is required");
        synchronized (tasks) {
            // 先构造稳定候选集合并排序，避免遍历 ConcurrentHashMap 导致优先级失效。
            List<AsyncTask> due = new ArrayList<>();
            for (AsyncTask task : tasks.values()) {
                if (task.getStatus() != null && task.getStatus().isPendingSubmission()
                    && task.getScheduledSubmitAt() <= now && task.getLeaseUntil() <= now) due.add(task);
            }
            due.sort(Comparator.comparingInt(AsyncTask::getPriority).reversed()
                .thenComparingLong(AsyncTask::getScheduledSubmitAt)
                .thenComparingLong(AsyncTask::getCreatedAt));
            List<AsyncTask> claimed = new ArrayList<>();
            for (AsyncTask candidate : due) {
                if (claimed.size() >= limit) break;
                // 准入判断与领取共用同一锁，账号/租户计数不会被本 Store 的并发领取穿透。
                if (!admissionPolicy.tryAcquire(candidate.copy(), snapshotValues(), now)) continue;
                AsyncTask task = candidate.copy();
                task.setStatus(AsyncTaskStatus.SUBMITTING);
                claim(task, workerId, now, leaseMillis);
                tasks.put(task.getId(), task.copy());
                claimed.add(task.copy());
            }
            return claimed;
        }
    }

    @Override
    public List<AsyncTask> claimDueTasks(String workerId, long now, long leaseMillis, int limit) {
        validateClaim(workerId, leaseMillis, limit);
        synchronized (tasks) {
            List<AsyncTask> due = new ArrayList<>();
            for (AsyncTask task : tasks.values()) {
                if (task.getStatus() != null && task.getStatus().isQueryable()
                    && task.getNextQueryAt() <= now && task.getLeaseUntil() <= now) due.add(task);
            }
            due.sort(Comparator.comparingLong(AsyncTask::getNextQueryAt));
            List<AsyncTask> claimed = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, due.size()); i++) {
                AsyncTask task = due.get(i).copy();
                claim(task, workerId, now, leaseMillis);
                tasks.put(task.getId(), task.copy());
                claimed.add(task.copy());
            }
            return claimed;
        }
    }

    @Override
    public AsyncTask renewLease(String taskId, String workerId, String leaseId, long now, long leaseUntil) {
        synchronized (tasks) {
            AsyncTask current = requireOwned(taskId, workerId, leaseId, now);
            AsyncTask renewed = current.copy();
            renewed.setLeaseUntil(leaseUntil);
            tasks.put(taskId, renewed);
            return renewed.copy();
        }
    }

    @Override
    public void releaseLease(String taskId, String workerId, String leaseId) {
        synchronized (tasks) {
            AsyncTask current = tasks.get(taskId);
            if (current == null || !equals(workerId, current.getLeaseOwner()) || !equals(leaseId, current.getLeaseId()))
                return;
            AsyncTask released = current.copy();
            released.setLeaseOwner(null);
            released.setLeaseId(null);
            released.setLeaseUntil(0);
            tasks.put(taskId, released);
        }
    }

    @Override
    public boolean requestCancellation(String taskId) {
        synchronized (tasks) {
            AsyncTask current = tasks.get(taskId);
            if (current == null || current.getStatus().isTerminal() || current.isCancellationRequested()) return false;
            AsyncTask updated = current.copy();
            updated.setCancellationRequested(true);
            updated.setVersion(updated.getVersion() + 1);
            tasks.put(taskId, updated);
            return true;
        }
    }

    private AsyncTask requireOwned(String taskId, String workerId, String leaseId, long now) {
        AsyncTask task = tasks.get(taskId);
        if (task == null || task.getLeaseUntil() <= now || !equals(workerId, task.getLeaseOwner())
            || !equals(leaseId, task.getLeaseId()))
            throw new IllegalStateException("Async task lease is not owned by worker: " + workerId);
        return task;
    }

    private void claim(AsyncTask task, String workerId, long now, long leaseMillis) {
        // 每次重新领取都生成新的 leaseId，即使 workerId 相同也能隔离旧一轮执行。
        task.setLeaseOwner(workerId);
        task.setLeaseId(UUID.randomUUID().toString());
        task.setLeaseUntil(now + leaseMillis);
        task.setVersion(task.getVersion() + 1);
    }

    private List<AsyncTask> snapshotValues() {
        List<AsyncTask> values = new ArrayList<>(tasks.size());
        for (AsyncTask task : tasks.values()) values.add(task.copy());
        return values;
    }

    private void validateClaim(String workerId, long leaseMillis, int limit) {
        if (workerId == null || leaseMillis <= 0 || limit <= 0) {
            throw new IllegalArgumentException("Invalid claim arguments");
        }
    }

    private void ensureLeaseNotOverwritten(AsyncTask task, AsyncTask current) {
        if (current.getLeaseId() != null && (!equals(current.getLeaseId(), task.getLeaseId())
            || !equals(current.getLeaseOwner(), task.getLeaseOwner()))) {
            throw new IllegalStateException("Async task lease does not match: " + task.getId());
        }
    }

    private void requireTask(AsyncTask task) {
        if (task == null || task.getId() == null) throw new IllegalArgumentException("task and task.id are required");
    }

    private boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}

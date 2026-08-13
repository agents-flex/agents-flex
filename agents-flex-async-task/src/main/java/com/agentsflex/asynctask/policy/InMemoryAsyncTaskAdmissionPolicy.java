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
package com.agentsflex.asynctask.policy;

import com.agentsflex.asynctask.AsyncTask;


import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 进程内准入控制实现；内存 Store 会在领取锁中调用 {@link #tryAcquire}。
 *
 * <p>供应商 QPS 使用最近 1000 毫秒滑动窗口；账号和租户只统计已经离开 PENDING_SUBMIT、
 * 且尚未进入终态的任务。本实现配置和计数不会跨 JVM 共享。</p>
 */
public final class InMemoryAsyncTaskAdmissionPolicy implements ConfigurableAsyncTaskAdmissionPolicy {
    private final Map<String, Integer> providerQps = new HashMap<>();
    private final Map<String, Integer> accountConcurrency = new HashMap<>();
    private final Map<String, Integer> tenantQuota = new HashMap<>();
    private final Map<String, Deque<Long>> providerAttempts = new HashMap<>();
    private final Set<String> pausedProviders = new HashSet<>();

    @Override
    public synchronized boolean tryAcquire(AsyncTask candidate, Collection<AsyncTask> allTasks, long now) {
        String provider = candidate.getProviderKey();
        // 暂停和容量限制先判断，只有真正允许提交的候选才会消耗一次 QPS 额度。
        if (pausedProviders.contains(provider)) return false;
        if (!hasTenantCapacity(candidate, allTasks) || !hasAccountCapacity(candidate, allTasks)) return false;
        int qps = providerQps.containsKey(provider) ? providerQps.get(provider) : Integer.MAX_VALUE;
        Deque<Long> attempts = providerAttempts.computeIfAbsent(provider, key -> new ArrayDeque<>());
        // 移除滑动窗口左边界及更早的记录，窗口定义为 (now - 1000, now]。
        while (!attempts.isEmpty() && attempts.peekFirst() <= now - 1000L) attempts.removeFirst();
        if (attempts.size() >= qps) return false;
        attempts.addLast(now);
        return true;
    }

    private boolean hasAccountCapacity(AsyncTask candidate, Collection<AsyncTask> tasks) {
        if (candidate.getAccountId() == null) return true;
        Integer limit = accountConcurrency.get(accountKey(candidate.getProviderKey(), candidate.getAccountId()));
        if (limit == null) return true;
        int active = 0;
        for (AsyncTask task : tasks) {
            if (same(candidate.getProviderKey(), task.getProviderKey())
                && same(candidate.getAccountId(), task.getAccountId()) && isActive(task)) active++;
        }
        return active < limit;
    }

    private boolean hasTenantCapacity(AsyncTask candidate, Collection<AsyncTask> tasks) {
        if (candidate.getTenantId() == null) return true;
        Integer limit = tenantQuota.get(candidate.getTenantId());
        if (limit == null) return true;
        int active = 0;
        for (AsyncTask task : tasks) {
            if (same(candidate.getTenantId(), task.getTenantId()) && isActive(task)) active++;
        }
        return active < limit;
    }

    private boolean isActive(AsyncTask task) {
        return task.getStatus() != null && !task.getStatus().isTerminal()
            && !task.getStatus().isPendingSubmission();
    }

    @Override
    public synchronized void setProviderQps(String key, int qps) {
        requireKey(key, "providerKey");
        if (qps <= 0) throw new IllegalArgumentException("qps must be greater than 0");
        providerQps.put(key, qps);
    }

    @Override
    public synchronized void setAccountConcurrency(String provider, String account, int limit) {
        requireKey(provider, "providerKey");
        requireKey(account, "accountId");
        if (limit <= 0) throw new IllegalArgumentException("limit must be greater than 0");
        accountConcurrency.put(accountKey(provider, account), limit);
    }

    @Override
    public synchronized void setTenantQuota(String tenant, int limit) {
        requireKey(tenant, "tenantId");
        if (limit <= 0) throw new IllegalArgumentException("activeTaskLimit must be greater than 0");
        tenantQuota.put(tenant, limit);
    }

    @Override
    public synchronized void pauseProvider(String key) {
        requireKey(key, "providerKey");
        pausedProviders.add(key);
    }

    @Override
    public synchronized void resumeProvider(String key) {
        requireKey(key, "providerKey");
        pausedProviders.remove(key);
    }

    @Override
    public synchronized boolean isProviderPaused(String key) {
        return pausedProviders.contains(key);
    }

    private String accountKey(String provider, String account) {
        return provider + "\u0000" + account;
    }

    private boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private void requireKey(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}

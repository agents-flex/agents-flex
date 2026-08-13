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
import com.agentsflex.asynctask.TaskQueryResult;


/**
 * 正常查询使用固定间隔、异常查询使用有上限指数退避的重试策略。
 *
 * <p>第 1 次连续异常使用 initialErrorDelayMillis，之后按 2 的幂增长；指数上限为 30，
 * 同时使用 maxErrorDelayMillis 防止溢出和过长等待。</p>
 */
public final class ExponentialAsyncTaskRetryPolicy implements AsyncTaskRetryPolicy {
    private final long queryIntervalMillis;
    private final long initialErrorDelayMillis;
    private final long maxErrorDelayMillis;
    private final int maxConsecutiveErrors;

    public ExponentialAsyncTaskRetryPolicy(long queryIntervalMillis, long initialErrorDelayMillis,
                                           long maxErrorDelayMillis, int maxConsecutiveErrors) {
        if (queryIntervalMillis <= 0 || initialErrorDelayMillis <= 0 || maxErrorDelayMillis <= 0
            || maxConsecutiveErrors < 0) throw new IllegalArgumentException("Invalid retry policy configuration");
        this.queryIntervalMillis = queryIntervalMillis;
        this.initialErrorDelayMillis = initialErrorDelayMillis;
        this.maxErrorDelayMillis = maxErrorDelayMillis;
        this.maxConsecutiveErrors = maxConsecutiveErrors;
    }

    @Override
    public long nextQueryDelayMillis(AsyncTask task, TaskQueryResult result) {
        return queryIntervalMillis;
    }

    @Override
    public long nextErrorDelayMillis(AsyncTask task, RuntimeException error) {
        // consecutiveErrors 在 Worker 调用本方法前已递增，因此减 1 后作为从 0 开始的指数。
        int exponent = Math.max(0, Math.min(30, task.getConsecutiveErrors() - 1));
        long multiplier = 1L << exponent;
        if (initialErrorDelayMillis > maxErrorDelayMillis / multiplier) return maxErrorDelayMillis;
        return Math.min(maxErrorDelayMillis, initialErrorDelayMillis * multiplier);
    }

    @Override
    public boolean shouldRetry(AsyncTask task, RuntimeException error) {
        return task.getConsecutiveErrors() <= maxConsecutiveErrors;
    }
}

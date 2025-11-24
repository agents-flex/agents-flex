/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.util;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 重试执行器。
 * <p>
 * 设计原则：
 * - Builder 仅用于构造配置
 * - Retryer 实例是线程安全的（无状态），可全局复用
 */
public final class Retryer {

    private final int maxRetries;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final boolean exponentialBackoff;
    private final long totalTimeoutMs;
    private final Predicate<Exception> retryOnException;
    private final Predicate<Object> retryOnResult;
    private final String operationName;

    private Retryer(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.initialDelayMs = builder.initialDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        this.exponentialBackoff = builder.exponentialBackoff;
        this.totalTimeoutMs = builder.totalTimeoutMs;
        this.retryOnException = builder.retryOnException;
        this.retryOnResult = builder.retryOnResult;
        this.operationName = builder.operationName;
    }

    public static Builder builder() {
        return new Builder();
    }


    public static <T> T retry(Callable<T> task, int maxRetries, long initialDelayMs) {
        return builder()
            .maxRetries(maxRetries)
            .initialDelayMs(initialDelayMs)
            .build()
            .execute(task);
    }

    public static void retry(Runnable task, int maxRetries, long initialDelayMs) {
        builder()
            .maxRetries(maxRetries)
            .initialDelayMs(initialDelayMs)
            .build()
            .execute(task);
    }


    public <T> T execute(Callable<T> task) {
        if (task == null) {
            throw new IllegalArgumentException("Task must not be null");
        }

        long deadline = totalTimeoutMs > 0 ? System.currentTimeMillis() + totalTimeoutMs : Long.MAX_VALUE;
        long currentDelay = initialDelayMs;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (Thread.interrupted()) {
                throw new RetryException(
                    "Retry interrupted at attempt " + attempt + " for: " + operationName,
                    new InterruptedException());
            }

            if (System.currentTimeMillis() > deadline) {
                throw new RetryException(
                    "Retry deadline exceeded after " + attempt + " attempts for: " + operationName,
                    new java.util.concurrent.TimeoutException());
            }

            try {
                T result = task.call();
                if (attempt < maxRetries && retryOnResult.test(result)) {
                    lastException = new RuntimeException("Retry triggered by result predicate");
                    sleepSafely(Math.min(currentDelay, deadline - System.currentTimeMillis()));
                    if (exponentialBackoff) {
                        currentDelay = Math.min(currentDelay * 2, maxDelayMs);
                    }
                    continue;
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries && retryOnException.test(e)) {
                    sleepSafely(Math.min(currentDelay, deadline - System.currentTimeMillis()));
                    if (exponentialBackoff) {
                        currentDelay = Math.min(currentDelay * 2, maxDelayMs);
                    }
                } else {
                    break;
                }
            }
        }

        if (lastException != null) {
            throw new RetryException(
                "Retry failed for: " + operationName + " after " + (maxRetries + 1) + " attempts",
                lastException);
        }

        throw new IllegalStateException("Retry loop exited without result or exception");
    }

    public void execute(Runnable task) {
        try {
            execute(() -> {
                task.run();
                return null;
            });
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Error in retryable Runnable", e);
        }
    }

    private void sleepSafely(long sleepMs) {
        if (sleepMs <= 0) return;
        try {
            TimeUnit.MILLISECONDS.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry backoff", e);
        }
    }


    public static class Builder {
        private int maxRetries = 2;
        private long initialDelayMs = 100;
        private long maxDelayMs = 5000;
        private boolean exponentialBackoff = false;
        private long totalTimeoutMs = 0;
        private Predicate<Exception> retryOnException = DEFAULT_RETRYABLE_EXCEPTION;
        private Predicate<Object> retryOnResult = r -> false;
        private String operationName = "retryable_operation";

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = Math.max(0, maxRetries);
            return this;
        }

        public Builder initialDelayMs(long delayMs) {
            this.initialDelayMs = Math.max(0, delayMs);
            return this;
        }

        public Builder maxDelayMs(long maxDelayMs) {
            this.maxDelayMs = Math.max(this.initialDelayMs, maxDelayMs);
            return this;
        }

        public Builder exponentialBackoff() {
            this.exponentialBackoff = true;
            return this;
        }

        public Builder totalTimeoutMs(long totalTimeoutMs) {
            this.totalTimeoutMs = Math.max(0, totalTimeoutMs);
            return this;
        }

        public Builder retryOnException(Predicate<Exception> predicate) {
            this.retryOnException = predicate != null ? predicate : DEFAULT_RETRYABLE_EXCEPTION;
            return this;
        }

        public Builder retryOnResult(Predicate<Object> predicate) {
            this.retryOnResult = predicate != null ? predicate : (r -> false);
            return this;
        }

        public Builder operationName(String name) {
            this.operationName = name != null ? name : "retryable_operation";
            return this;
        }

        public Retryer build() {
            return new Retryer(this);
        }
    }


    private static final Predicate<Exception> DEFAULT_RETRYABLE_EXCEPTION = e ->
        e instanceof java.net.SocketTimeoutException ||
            e instanceof java.net.ConnectException ||
            e instanceof java.net.UnknownHostException ||
            e instanceof java.io.IOException ||
            (e.getCause() instanceof java.net.SocketTimeoutException) ||
            (e.getCause() instanceof java.net.ConnectException);
}

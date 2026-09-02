/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * AgentWorker 的租约和自动轮询配置。
 */
public final class AgentWorkerOptions {
    /**
     * Worker 在共享 Store 中使用的稳定身份，多个进程不应重复。
     */
    private final String workerId;
    private final long leaseMillis;
    private final long pollIntervalMillis;
    private final int batchSize;
    private final int maxConcurrentTurns;
    /**
     * 租约续期间隔占租约时长的比例，默认三分之一。
     */
    private final double leaseRenewalFraction;

    private AgentWorkerOptions(Builder builder) {
        if (builder.workerId == null || builder.workerId.trim().isEmpty())
            throw new IllegalArgumentException("workerId must not be blank");
        if (builder.leaseMillis <= 0 || builder.pollIntervalMillis <= 0 || builder.batchSize <= 0
            || builder.maxConcurrentTurns <= 0
            || Double.isNaN(builder.leaseRenewalFraction)
            || Double.isInfinite(builder.leaseRenewalFraction)
            || builder.leaseRenewalFraction <= 0 || builder.leaseRenewalFraction > 1) {
            throw new IllegalArgumentException(
                "lease, poll interval, batch size and leaseRenewalFraction must be valid");
        }
        workerId = builder.workerId;
        leaseMillis = builder.leaseMillis;
        pollIntervalMillis = builder.pollIntervalMillis;
        batchSize = builder.batchSize;
        maxConcurrentTurns = builder.maxConcurrentTurns;
        leaseRenewalFraction = builder.leaseRenewalFraction;
    }

    /**
     * 创建 Worker 配置构建器。
     *
     * @param workerId    Worker 身份；用于租约所有者和日志关联
     * @param leaseMillis 单次租约有效期，必须大于 0
     */
    public static Builder builder(String workerId, long leaseMillis) {
        return new Builder().workerId(workerId).leaseMillis(leaseMillis);
    }

    /**
     * @return Worker 稳定身份
     */
    public String getWorkerId() {
        return workerId;
    }

    /**
     * @return 租约有效期（毫秒）
     */
    public long getLeaseMillis() {
        return leaseMillis;
    }

    /**
     * @return 自动轮询间隔（毫秒）
     */
    public long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    /**
     * @return 每轮最多领取的 Turn 数量
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * @return 单个 Worker 同时推进的最大 Turn 数量
     */
    public int getMaxConcurrentTurns() {
        return maxConcurrentTurns;
    }

    /**
     * @return 续租间隔占租约时长的比例，范围为 (0, 1]
     */
    public double getLeaseRenewalFraction() {
        return leaseRenewalFraction;
    }

    public static final class Builder {
        private String workerId;
        private long leaseMillis;
        private long pollIntervalMillis = 1000;
        private int batchSize = 1;
        private int maxConcurrentTurns = 1;
        private double leaseRenewalFraction = 1.0 / 3.0;

        /**
         * 设置租约所有者身份。
         */
        public Builder workerId(String value) {
            workerId = value;
            return this;
        }

        /**
         * 设置租约时长；Worker 会按约三分之一周期续租。
         */
        public Builder leaseMillis(long value) {
            leaseMillis = value;
            return this;
        }

        /**
         * 设置自动轮询间隔；只影响 {@code startPolling()}。
         */
        public Builder pollIntervalMillis(long value) {
            pollIntervalMillis = value;
            return this;
        }

        /**
         * 设置每次轮询最多领取的任务数。
         */
        public Builder batchSize(int value) {
            batchSize = value;
            return this;
        }

        /**
         * 设置单个 Worker 同时推进的最大 Turn 数，与每轮领取总量相互独立。
         */
        public Builder maxConcurrentTurns(int value) {
            maxConcurrentTurns = value;
            return this;
        }

        /**
         * 设置租约续期间隔比例；例如 1/3 表示每个租约周期续租三次。
         */
        public Builder leaseRenewalFraction(double value) {
            leaseRenewalFraction = value;
            return this;
        }

        /**
         * 校验并冻结 Worker 配置。
         */
        public AgentWorkerOptions build() {
            return new AgentWorkerOptions(this);
        }
    }
}

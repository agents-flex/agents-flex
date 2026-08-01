/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import java.io.Serializable;

/**
 * 模型或工具发生可恢复异常时使用的重试与退避策略。
 *
 * <p>{@code maxRetries} 只计算首次执行失败后的重试次数，不包含首次执行。等待时间按照增长倍数
 * 逐次增加，并受最大等待时间限制。</p>
 */
public final class AgentRetryPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 首次执行失败后允许安排的最大重试次数。 */
    private final int maxRetries;
    /** 第一次重试前的等待时间。 */
    private final long initialDelayMillis;
    /** 单次重试允许等待的最长时间。 */
    private final long maxDelayMillis;
    /** 相邻两次重试等待时间的增长倍数。 */
    private final double multiplier;

    private AgentRetryPolicy(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.initialDelayMillis = builder.initialDelayMillis;
        this.maxDelayMillis = builder.maxDelayMillis;
        this.multiplier = builder.multiplier;
    }

    /** @return 禁用自动重试的共享语义策略 */
    public static AgentRetryPolicy none() {
        return builder().build();
    }

    /** @return 新的指数退避重试策略构建器 */
    public static Builder builder() {
        return new Builder();
    }

    /** @return 首次失败后最多安排的重试次数 */
    public int getMaxRetries() { return maxRetries; }
    /** @return 第一次重试前等待毫秒数 */
    public long getInitialDelayMillis() { return initialDelayMillis; }
    /** @return 单次退避等待的最大毫秒数 */
    public long getMaxDelayMillis() { return maxDelayMillis; }
    /** @return 相邻重试延迟的增长倍数 */
    public double getMultiplier() { return multiplier; }

    /** 根据已安排的重试次数计算下一次等待时间。 */
    public long delayMillis(int retryCount) {
        if (retryCount <= 0) {
            return initialDelayMillis;
        }
        double value = initialDelayMillis * Math.pow(multiplier, retryCount - 1);
        return Math.min(maxDelayMillis, (long) value);
    }

    /** 构建重试策略。 */
    public static final class Builder {
        private int maxRetries;
        private long initialDelayMillis = 1000;
        private long maxDelayMillis = 60000;
        private double multiplier = 2.0d;

        /** 设置首次失败后最多允许的重试次数。 */
        public Builder maxRetries(int value) { this.maxRetries = value; return this; }

        /** 设置第一次重试前的等待毫秒数。 */
        public Builder initialDelayMillis(long value) { this.initialDelayMillis = value; return this; }

        /** 设置单次退避等待的最大毫秒数。 */
        public Builder maxDelayMillis(long value) { this.maxDelayMillis = value; return this; }

        /** 设置相邻两次重试等待时间的增长倍数。 */
        public Builder multiplier(double value) { this.multiplier = value; return this; }

        public AgentRetryPolicy build() {
            if (maxRetries < 0 || initialDelayMillis < 0 || maxDelayMillis < initialDelayMillis
                || multiplier < 1.0d) {
                throw new IllegalStateException("invalid retry policy");
            }
            return new AgentRetryPolicy(this);
        }
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import java.io.Serializable;

/**
 * AgentTurn 可消耗资源的硬性上限。
 *
 * <p>值为 0 表示不限制。Runner 会在外部调用前检查时间和调用次数，并在模型返回后检查
 * Token 用量。超过任一上限后，Turn 会进入 {@link AgentTurnStatus#BUDGET_EXCEEDED}。</p>
 */
public final class AgentBudget implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 从 Turn 创建开始计算的最长运行时间。
     */
    private final long maxDurationMillis;
    /**
     * 模型累计输入 Token 上限。
     */
    private final long maxInputTokens;
    /**
     * 模型累计输出 Token 上限。
     */
    private final long maxOutputTokens;
    /**
     * 模型累计总 Token 上限。
     */
    private final long maxTotalTokens;
    /**
     * 实际开始执行的工具调用次数上限。
     */
    private final int maxToolCalls;

    /**
     * 从已校验构建器创建不可变资源预算。
     */
    private AgentBudget(Builder builder) {
        this.maxDurationMillis = builder.maxDurationMillis;
        this.maxInputTokens = builder.maxInputTokens;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.maxTotalTokens = builder.maxTotalTokens;
        this.maxToolCalls = builder.maxToolCalls;
    }

    /**
     * 创建所有限制均关闭的预算。
     */
    public static AgentBudget unlimited() {
        return builder().build();
    }

    /**
     * 创建预算构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    public long getMaxDurationMillis() {
        return maxDurationMillis;
    }

    /**
     * @return 累计输入 Token 上限，0 表示不限制
     */
    public long getMaxInputTokens() {
        return maxInputTokens;
    }

    /**
     * @return 累计输出 Token 上限，0 表示不限制
     */
    public long getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * @return 模型报告的累计总 Token 上限，0 表示不限制
     */
    public long getMaxTotalTokens() {
        return maxTotalTokens;
    }

    /**
     * @return 业务工具调用次数上限，0 表示不限制
     */
    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    /**
     * 构建不可变预算配置。
     */
    public static final class Builder {
        private long maxDurationMillis;
        private long maxInputTokens;
        private long maxOutputTokens;
        private long maxTotalTokens;
        private int maxToolCalls;

        /**
         * 设置最长运行毫秒数，0 表示不限制。
         */
        public Builder maxDurationMillis(long value) {
            this.maxDurationMillis = value;
            return this;
        }

        /**
         * 设置累计输入 Token 上限，0 表示不限制。
         */
        public Builder maxInputTokens(long value) {
            this.maxInputTokens = value;
            return this;
        }

        /**
         * 设置累计输出 Token 上限，0 表示不限制。
         */
        public Builder maxOutputTokens(long value) {
            this.maxOutputTokens = value;
            return this;
        }

        /**
         * 设置累计总 Token 上限，0 表示不限制。
         */
        public Builder maxTotalTokens(long value) {
            this.maxTotalTokens = value;
            return this;
        }

        /**
         * 设置工具执行次数上限，0 表示不限制。
         */
        public Builder maxToolCalls(int value) {
            this.maxToolCalls = value;
            return this;
        }

        /**
         * 校验所有限制均为非负数后创建预算。
         *
         * @return 不可变预算
         * @throws IllegalStateException 任一限制为负数时抛出
         */
        public AgentBudget build() {
            if (maxDurationMillis < 0 || maxInputTokens < 0 || maxOutputTokens < 0
                || maxTotalTokens < 0 || maxToolCalls < 0) {
                throw new IllegalStateException("budget values must not be negative");
            }
            return new AgentBudget(this);
        }
    }
}

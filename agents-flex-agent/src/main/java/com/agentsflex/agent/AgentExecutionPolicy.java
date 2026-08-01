/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.tool.ToolErrorStrategy;

import java.io.Serializable;

/**
 * Agent 单次运行的执行策略。
 *
 * <p>该对象本身不可变，负责以下执行控制：</p>
 * <ul>
 *     <li>限制一次运行最多允许多少次模型调用，避免模型与工具无限循环；</li>
 *     <li>限制运行模式最多推进多少个 step，避免自定义模式无限循环；</li>
 *     <li>规定工具执行失败后，是立即终止运行，还是把结构化错误返回给模型继续处理。</li>
 *     <li>为可恢复异常配置自动重试和退避时间；</li>
 *     <li>限制运行时间、Token 和工具调用次数。</li>
 * </ul>
 *
 * <p>策略属于 Agent 定义的一部分，会应用到该 Agent 创建的每一个 {@link AgentRun}。</p>
 */
public final class AgentExecutionPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认最多允许调用模型 20 次。
     */
    private static final int DEFAULT_MAX_ITERATIONS = 20;
    /** 默认允许自定义运行模式推进的最大步骤数。 */
    private static final int DEFAULT_MAX_STEPS = 1000;

    /**
     * 一次运行允许的最大模型调用次数。
     */
    private final int maxIterations;
    /** 一次 Run 允许执行模式推进的最大 step 次数。 */
    private final int maxSteps;
    /**
     * 工具调用失败时采用的处理方式。
     */
    private final ToolErrorStrategy toolErrorStrategy;
    /** 模型或工具发生可恢复异常时使用的重试策略。 */
    private final AgentRetryPolicy retryPolicy;
    /** 限制运行时间、Token 和工具调用次数的资源预算。 */
    private final AgentBudget budget;

    private AgentExecutionPolicy(Builder builder) {
        this.maxIterations = builder.maxIterations;
        this.maxSteps = builder.maxSteps;
        this.toolErrorStrategy = builder.toolErrorStrategy;
        this.retryPolicy = builder.retryPolicy;
        this.budget = builder.budget;
    }

    /**
     * 创建使用框架默认值的执行策略。
     */
    public static AgentExecutionPolicy defaults() {
        return builder().build();
    }

    /**
     * 创建执行策略构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 一次 AgentRun 允许的最大模型调用次数
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    public int getMaxSteps() { return maxSteps; }

    /**
     * @return 工具执行失败后的处理策略
     */
    public ToolErrorStrategy getToolErrorStrategy() {
        return toolErrorStrategy;
    }

    /** @return 可恢复异常的自动重试策略 */
    public AgentRetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    /** @return 本次运行必须遵守的资源预算 */
    public AgentBudget getBudget() {
        return budget;
    }

    /**
     * 执行策略构建器。
     */
    public static final class Builder {

        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private ToolErrorStrategy toolErrorStrategy = ToolErrorStrategy.FAIL_RUN;
        private AgentRetryPolicy retryPolicy = AgentRetryPolicy.none();
        private AgentBudget budget = AgentBudget.unlimited();

        /**
         * 设置最大模型迭代次数。
         *
         * @param maxIterations 必须大于 0
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /** 设置运行模式最多允许推进多少个 step。 */
        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        /**
         * 设置工具执行失败后的处理策略。
         */
        public Builder toolErrorStrategy(ToolErrorStrategy toolErrorStrategy) {
            this.toolErrorStrategy = toolErrorStrategy;
            return this;
        }

        /** 设置可恢复异常的最大重试次数与退避方式。 */
        public Builder retryPolicy(AgentRetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        /** 设置时间、Token 和工具调用次数的硬限制。 */
        public Builder budget(AgentBudget budget) {
            this.budget = budget;
            return this;
        }

        /**
         * 构建不可变执行策略。
         *
         * @throws IllegalStateException 最大迭代次数非法或错误策略为空时抛出
         */
        public AgentExecutionPolicy build() {
            if (maxIterations <= 0 || maxSteps <= 0) {
                throw new IllegalStateException("maxIterations and maxSteps must be greater than 0");
            }
            if (toolErrorStrategy == null) {
                throw new IllegalStateException("toolErrorStrategy must not be null");
            }
            if (retryPolicy == null || budget == null) {
                throw new IllegalStateException("retryPolicy and budget must not be null");
            }
            return new AgentExecutionPolicy(this);
        }
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.tool.ToolErrorStrategy;
import com.agentsflex.agent.tool.ToolErrorMessageFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

/**
 * Agent 单次运行的执行策略。
 *
 * <p>该对象本身不可变，负责以下执行控制：</p>
 * <ul>
 *     <li>限制一次运行最多允许多少次模型调用，避免模型与工具无限循环；</li>
 *     <li>限制 Runner 最多推进多少个 step，避免非模型路径无限循环；</li>
 *     <li>规定工具执行失败后，是立即终止运行，还是把结构化错误返回给模型继续处理。</li>
 *     <li>为可恢复异常配置自动重试和退避时间；</li>
 *     <li>限制运行时间、Token 和工具调用次数。</li>
 * </ul>
 *
 * <p>策略属于 Agent 定义的一部分，会应用到该 Agent 创建的每一个 {@link AgentTurn}。</p>
 */
public final class AgentExecutionPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认最多允许调用模型 20 次。
     */
    private static final int DEFAULT_MAX_ITERATIONS = 100;
    /**
     * 默认允许 Runner 推进的最大步骤数。
     */
    private static final int DEFAULT_MAX_STEPS = 1000;
    private static final String DEFAULT_INTERRUPTED_TOOL_MESSAGE =
        "Tool call was not completed: {reason}";
    private static final String DEFAULT_INTERRUPTED_TURN_MESSAGE =
        "The previous AgentTurn ended before completion: {reason}";
    private static final String DEFAULT_CANCELLATION_REASON =
        "turn cancelled by caller";

    /**
     * 一次运行允许的最大模型调用次数。
     */
    private final int maxIterations;
    /**
     * 一次 Turn 允许 Runner 推进的最大 step 次数。
     */
    private final int maxSteps;
    /**
     * 工具调用失败时采用的处理方式。
     */
    private final ToolErrorStrategy toolErrorStrategy;
    /**
     * 将允许交回模型的工具异常转换为 ToolMessage 的业务规则。
     */
    // A factory is executable process-local behavior, not durable turn state.
    // Do not let a user-provided lambda make an otherwise serializable snapshot fail.
    private transient ToolErrorMessageFactory toolErrorMessageFactory;
    /**
     * 模型或工具发生可恢复异常时使用的重试策略。
     */
    private final AgentRetryPolicy retryPolicy;
    /**
     * 限制运行时间、Token 和工具调用次数的资源预算。
     */
    private final AgentBudget budget;
    /**
     * 未完成 ToolCall 收束为 ToolMessage 时使用的内容模板。
     */
    private final String interruptedToolMessageTemplate;
    /**
     * 异常终止 Turn 最后追加的 AIMessage 内容模板。
     */
    private final String interruptedTurnMessageTemplate;
    /**
     * 主动取消 Turn 时写入收束消息的原因文本。
     */
    private final String cancellationReason;

    private AgentExecutionPolicy(Builder builder) {
        this.maxIterations = builder.maxIterations;
        this.maxSteps = builder.maxSteps;
        this.toolErrorStrategy = builder.toolErrorStrategy;
        this.toolErrorMessageFactory = builder.toolErrorMessageFactory;
        this.retryPolicy = builder.retryPolicy;
        this.budget = builder.budget;
        this.interruptedToolMessageTemplate = builder.interruptedToolMessageTemplate;
        this.interruptedTurnMessageTemplate = builder.interruptedTurnMessageTemplate;
        this.cancellationReason = builder.cancellationReason;
    }

    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (toolErrorMessageFactory == null) {
            toolErrorMessageFactory = ToolErrorMessageFactory.defaultFactory();
        }
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
     * @return 一次 AgentTurn 允许的最大模型调用次数
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * @return 一次 AgentTurn 允许 Runner 推进的最大 step 次数
     */
    public int getMaxSteps() {
        return maxSteps;
    }

    /**
     * @return 工具执行失败后的处理策略
     */
    public ToolErrorStrategy getToolErrorStrategy() {
        return toolErrorStrategy;
    }

    /**
     * @return 工具异常交回模型时使用的消息工厂
     */
    public ToolErrorMessageFactory getToolErrorMessageFactory() {
        return toolErrorMessageFactory;
    }

    /**
     * @return 可恢复异常的自动重试策略
     */
    public AgentRetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    /**
     * @return 本次运行必须遵守的资源预算
     */
    public AgentBudget getBudget() {
        return budget;
    }

    /**
     * @return 未完成 ToolCall 的收束消息模板
     */
    public String getInterruptedToolMessageTemplate() {
        return interruptedToolMessageTemplate;
    }

    /**
     * @return 异常终止 Turn 的最终说明模板
     */
    public String getInterruptedTurnMessageTemplate() {
        return interruptedTurnMessageTemplate;
    }

    /**
     * @return 主动取消 Turn 时使用的原因文本
     */
    public String getCancellationReason() {
        return cancellationReason;
    }

    /**
     * 执行策略构建器。
     */
    public static final class Builder {

        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private ToolErrorStrategy toolErrorStrategy = ToolErrorStrategy.FAIL_RUN;
        private ToolErrorMessageFactory toolErrorMessageFactory =
            ToolErrorMessageFactory.defaultFactory();
        private AgentRetryPolicy retryPolicy = AgentRetryPolicy.none();
        private AgentBudget budget = AgentBudget.unlimited();
        private String interruptedToolMessageTemplate = DEFAULT_INTERRUPTED_TOOL_MESSAGE;
        private String interruptedTurnMessageTemplate = DEFAULT_INTERRUPTED_TURN_MESSAGE;
        private String cancellationReason = DEFAULT_CANCELLATION_REASON;

        /**
         * 设置最大模型迭代次数。
         *
         * @param maxIterations 必须大于 0
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * 设置 Runner 最多允许推进多少个 step。
         */
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

        /**
         * 设置工具执行异常交回模型时的消息构造规则。
         *
         * <p>仅在 {@link ToolErrorStrategy#RETURN_ERROR_TO_MODEL} 时调用，可用于隐藏内部异常详情、
         * 映射业务错误码或告诉模型可采取的补救动作。</p>
         */
        public Builder toolErrorMessageFactory(ToolErrorMessageFactory value) {
            this.toolErrorMessageFactory = value;
            return this;
        }

        /**
         * 设置可恢复异常的最大重试次数与退避方式。
         */
        public Builder retryPolicy(AgentRetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        /**
         * 设置时间、Token 和工具调用次数的硬限制。
         */
        public Builder budget(AgentBudget budget) {
            this.budget = budget;
            return this;
        }

        /**
         * 设置未完成 ToolCall 对应的 ToolMessage 内容模板。
         *
         * <p>支持 {@code {reason}}、{@code {turnId}}、{@code {toolCallId}} 和
         * {@code {toolName}} 占位符。</p>
         */
        public Builder interruptedToolMessageTemplate(String value) {
            this.interruptedToolMessageTemplate = value;
            return this;
        }

        /**
         * 设置异常终止 Turn 最后追加的 AIMessage 内容模板。
         *
         * <p>支持 {@code {reason}} 和 {@code {turnId}} 占位符；工具相关占位符会替换为空字符串。</p>
         */
        public Builder interruptedTurnMessageTemplate(String value) {
            this.interruptedTurnMessageTemplate = value;
            return this;
        }

        /**
         * 设置主动取消 Turn 时使用的原因文本，该文本会作为 {@code {reason}} 注入收束消息模板。
         */
        public Builder cancellationReason(String value) {
            this.cancellationReason = value;
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
            if (retryPolicy == null || budget == null || toolErrorMessageFactory == null) {
                throw new IllegalStateException(
                    "retryPolicy, budget and toolErrorMessageFactory must not be null");
            }
            if (interruptedToolMessageTemplate == null || interruptedTurnMessageTemplate == null
                || cancellationReason == null) {
                throw new IllegalStateException(
                    "interrupted message templates and cancellationReason must not be null");
            }
            return new AgentExecutionPolicy(this);
        }
    }
}

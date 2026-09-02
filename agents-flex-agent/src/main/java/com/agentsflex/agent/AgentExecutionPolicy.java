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
 *     <li>分别限制审批、用户输入和外部工具挂起的等待时间。</li>
 *     <li>限制并行工具批次的并发度，并定义批次失败语义。</li>
 *     <li>限制工具结果大小，并决定超限时严格失败还是带标记截断。</li>
 * </ul>
 *
 * <p>策略属于 Agent 定义的一部分，会应用到该 Agent 创建的每一个 {@link AgentTurn}。</p>
 * <p>并行 ToolCall 仅适用于相互独立的本地工具；审批、表单和外部工具仍按可恢复的顺序状态机执行。
 * 并行工具应具备幂等性，因为超时或进程故障无法撤销已经发生的外部副作用。</p>
 */
public final class AgentExecutionPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认最多允许调用模型 100 次。
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
    /**
     * 单次模型调用最长允许执行时间，0 表示不单独限制。
     */
    private final long modelCallTimeoutMillis;
    /**
     * 单次本地工具调用最长允许执行时间，0 表示不单独限制。
     */
    private final long toolExecutionTimeoutMillis;
    /**
     * 外部工具结果最长等待时间，0 表示不设置过期时间。
     */
    private final long externalToolTimeoutMillis;
    /**
     * 审批挂起最长等待时间，0 表示不限制。
     */
    private final long approvalTimeoutMillis;
    /**
     * 用户输入挂起最长等待时间，0 表示不限制。
     */
    private final long userInputTimeoutMillis;
    /**
     * 挂起过期后的终态处理方式。
     */
    private final AgentSuspensionExpirationStrategy suspensionExpirationStrategy;
    /**
     * 本地工具结果允许写入模型上下文的最大字符数，0 表示不限制。
     */
    private final long toolResultMaxCharacters;
    /**
     * 外部工具结果允许写入模型上下文的最大字符数，0 表示不限制。
     */
    private final long externalToolResultMaxCharacters;
    /**
     * 工具结果超出字符上限时的处理策略。
     */
    private final AgentToolResultOverflowStrategy toolResultOverflowStrategy;
    /**
     * 同一模型响应中的多个本地工具调用执行方式。
     */
    private final AgentToolExecutionMode toolExecutionMode;
    /**
     * 并行批次最多同时启动的本地工具数量。
     */
    private final int maxParallelToolCalls;
    /**
     * 并行批次失败后的处理方式。
     */
    private final AgentParallelFailureStrategy parallelFailureStrategy;
    /**
     * 进程内重试分类器，不进入 Snapshot。
     */
    private transient AgentRetryClassifier retryClassifier;

    /**
     * 从已校验构建器冻结一次 Turn 使用的全部执行控制策略。
     */
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
        this.modelCallTimeoutMillis = builder.modelCallTimeoutMillis;
        this.toolExecutionTimeoutMillis = builder.toolExecutionTimeoutMillis;
        this.externalToolTimeoutMillis = builder.externalToolTimeoutMillis;
        this.approvalTimeoutMillis = builder.approvalTimeoutMillis;
        this.userInputTimeoutMillis = builder.userInputTimeoutMillis;
        this.suspensionExpirationStrategy = builder.suspensionExpirationStrategy;
        this.toolResultMaxCharacters = builder.toolResultMaxCharacters;
        this.externalToolResultMaxCharacters = builder.externalToolResultMaxCharacters;
        this.toolResultOverflowStrategy = builder.toolResultOverflowStrategy;
        this.toolExecutionMode = builder.toolExecutionMode;
        this.maxParallelToolCalls = builder.maxParallelToolCalls;
        this.parallelFailureStrategy = builder.parallelFailureStrategy;
        this.retryClassifier = builder.retryClassifier;
    }

    /**
     * 反序列化持久化策略，并恢复不会进入 Snapshot 的进程内错误消息工厂。
     *
     * @param input Java 对象输入流
     */
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (toolErrorMessageFactory == null) {
            toolErrorMessageFactory = ToolErrorMessageFactory.defaultFactory();
        }
        if (retryClassifier == null) {
            retryClassifier = AgentRetryClassifier.defaults();
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
     * @return 单次同步或流式模型调用的超时时间；0 表示不额外限制
     */
    public long getModelCallTimeoutMillis() {
        return modelCallTimeoutMillis;
    }

    /**
     * @return 单次本地工具调用的超时时间；0 表示不额外限制
     */
    public long getToolExecutionTimeoutMillis() {
        return toolExecutionTimeoutMillis;
    }

    /**
     * @return 外部工具结果等待时间；0 表示不判定结果过期
     */
    public long getExternalToolTimeoutMillis() {
        return externalToolTimeoutMillis;
    }

    public long getApprovalTimeoutMillis() {
        return approvalTimeoutMillis;
    }

    public long getUserInputTimeoutMillis() {
        return userInputTimeoutMillis;
    }

    public AgentSuspensionExpirationStrategy getSuspensionExpirationStrategy() {
        return suspensionExpirationStrategy == null
            ? AgentSuspensionExpirationStrategy.REJECT_RESUME : suspensionExpirationStrategy;
    }

    /**
     * @return 本地工具结果写入模型上下文的字符上限；0 表示不限制
     */
    public long getToolResultMaxCharacters() {
        return toolResultMaxCharacters;
    }

    /**
     * @return 外部工具结果写入模型上下文的字符上限；0 表示不限制
     */
    public long getExternalToolResultMaxCharacters() {
        return externalToolResultMaxCharacters;
    }

    /**
     * @return 工具结果超限时的处理方式；旧 Snapshot 缺少该字段时返回严格失败
     */
    public AgentToolResultOverflowStrategy getToolResultOverflowStrategy() {
        return toolResultOverflowStrategy == null
            ? AgentToolResultOverflowStrategy.FAIL : toolResultOverflowStrategy;
    }

    /**
     * @return 进程内重试分类器；旧 Snapshot 恢复后若分类器不可序列化则返回默认分类器
     */
    public AgentRetryClassifier getRetryClassifier() {
        return retryClassifier == null ? AgentRetryClassifier.defaults() : retryClassifier;
    }

    /**
     * 从已加载的同版本 Agent 策略重新绑定不进入 Snapshot 的进程内回调。
     *
     * <p>数值限制和持久化策略继续使用 Turn 创建时的快照值；这里只恢复无法可靠序列化的函数对象，
     * 防止任务跨进程恢复后静默改用默认错误映射或默认重试分类。</p>
     */
    void rebindRuntimeComponents(AgentExecutionPolicy source) {
        if (source == null) return;
        this.toolErrorMessageFactory = source.getToolErrorMessageFactory();
        this.retryClassifier = source.getRetryClassifier();
    }

    /**
     * @return 同一模型响应中本地 ToolCall 的执行方式
     */
    public AgentToolExecutionMode getToolExecutionMode() {
        return toolExecutionMode == null ? AgentToolExecutionMode.SEQUENTIAL : toolExecutionMode;
    }

    /**
     * @return 并行批次最大并发工具数
     */
    public int getMaxParallelToolCalls() {
        // 兼容反序列化的旧策略：新增字段缺省为 0 时回到安全默认上限。
        return maxParallelToolCalls <= 0 ? 8 : maxParallelToolCalls;
    }

    /**
     * @return 并行批次失败处理方式
     */
    public AgentParallelFailureStrategy getParallelFailureStrategy() {
        return parallelFailureStrategy == null
            ? AgentParallelFailureStrategy.FAIL_FAST : parallelFailureStrategy;
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
        private long modelCallTimeoutMillis;
        private long toolExecutionTimeoutMillis;
        private long externalToolTimeoutMillis;
        private long approvalTimeoutMillis;
        private long userInputTimeoutMillis;
        private AgentSuspensionExpirationStrategy suspensionExpirationStrategy =
            AgentSuspensionExpirationStrategy.REJECT_RESUME;
        private long toolResultMaxCharacters;
        private long externalToolResultMaxCharacters;
        private AgentToolResultOverflowStrategy toolResultOverflowStrategy =
            AgentToolResultOverflowStrategy.FAIL;
        private AgentRetryClassifier retryClassifier = AgentRetryClassifier.defaults();
        private AgentToolExecutionMode toolExecutionMode = AgentToolExecutionMode.SEQUENTIAL;
        private int maxParallelToolCalls = 8;
        private AgentParallelFailureStrategy parallelFailureStrategy = AgentParallelFailureStrategy.FAIL_FAST;

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
         * 设置单次模型调用超时。超时后任务会被取消并进入统一失败/重试流程；0 表示关闭单次限制。
         */
        public Builder modelCallTimeoutMillis(long value) {
            this.modelCallTimeoutMillis = value;
            return this;
        }

        /**
         * 设置单次本地工具调用超时；工具线程会收到取消信号，0 表示关闭限制。
         */
        public Builder toolExecutionTimeoutMillis(long value) {
            this.toolExecutionTimeoutMillis = value;
            return this;
        }

        /**
         * 设置外部工具回传的最长等待时间；超时后的回传会被拒绝，0 表示不设置期限。
         */
        public Builder externalToolTimeoutMillis(long value) {
            this.externalToolTimeoutMillis = value;
            return this;
        }

        /**
         * 设置工具审批挂起的最长等待时间；0 表示不设置期限。
         */
        public Builder approvalTimeoutMillis(long value) {
            this.approvalTimeoutMillis = value;
            return this;
        }

        /**
         * 设置用户输入挂起的最长等待时间；0 表示不设置期限。
         */
        public Builder userInputTimeoutMillis(long value) {
            this.userInputTimeoutMillis = value;
            return this;
        }

        /**
         * 设置挂起过期后拒绝命令、失败 Turn 或取消 Turn。
         */
        public Builder suspensionExpirationStrategy(AgentSuspensionExpirationStrategy value) {
            this.suspensionExpirationStrategy = value == null
                ? AgentSuspensionExpirationStrategy.REJECT_RESUME : value;
            return this;
        }

        /**
         * 限制本地工具结果写入模型上下文的字符数，防止工具输出耗尽上下文窗口。
         */
        public Builder toolResultMaxCharacters(long value) {
            this.toolResultMaxCharacters = value;
            return this;
        }

        /**
         * 限制外部执行器回传结果的字符数，超限结果不会写入 Turn。
         */
        public Builder externalToolResultMaxCharacters(long value) {
            this.externalToolResultMaxCharacters = value;
            return this;
        }

        /**
         * 设置本地和外部工具结果超限时的处理方式。默认严格失败；截断模式会保留明确的截断标记。
         */
        public Builder toolResultOverflowStrategy(AgentToolResultOverflowStrategy value) {
            this.toolResultOverflowStrategy = value == null
                ? AgentToolResultOverflowStrategy.FAIL : value;
            return this;
        }

        /**
         * 设置进程内重试分类器；它不会进入 Snapshot，恢复后使用恢复进程中的配置。
         */
        public Builder retryClassifier(AgentRetryClassifier value) {
            this.retryClassifier = value;
            return this;
        }

        /**
         * 设置同一模型回合内多个本地 ToolCall 的执行方式。
         */
        public Builder toolExecutionMode(AgentToolExecutionMode value) {
            this.toolExecutionMode = value == null
                ? AgentToolExecutionMode.SEQUENTIAL : value;
            return this;
        }

        /**
         * 设置并行批次最大并发工具数；超过上限时自动回退顺序执行。
         */
        public Builder maxParallelToolCalls(int value) {
            this.maxParallelToolCalls = value;
            return this;
        }

        /**
         * 设置并行批次出现失败时是快速失败还是将错误交回模型。
         */
        public Builder parallelFailureStrategy(AgentParallelFailureStrategy value) {
            this.parallelFailureStrategy = value == null
                ? AgentParallelFailureStrategy.FAIL_FAST : value;
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
            if (modelCallTimeoutMillis < 0 || toolExecutionTimeoutMillis < 0
                || externalToolTimeoutMillis < 0 || approvalTimeoutMillis < 0
                || userInputTimeoutMillis < 0 || toolResultMaxCharacters < 0
                || externalToolResultMaxCharacters < 0) {
                throw new IllegalStateException("timeout values must not be negative");
            }
            if (retryClassifier == null) {
                throw new IllegalStateException("retryClassifier must not be null");
            }
            if (maxParallelToolCalls <= 0 || parallelFailureStrategy == null) {
                throw new IllegalStateException(
                    "maxParallelToolCalls must be greater than 0 and parallelFailureStrategy must not be null");
            }
            if (toolResultOverflowStrategy == null) {
                throw new IllegalStateException("toolResultOverflowStrategy must not be null");
            }
            if (suspensionExpirationStrategy == null) {
                throw new IllegalStateException("suspensionExpirationStrategy must not be null");
            }
            return new AgentExecutionPolicy(this);
        }
    }
}

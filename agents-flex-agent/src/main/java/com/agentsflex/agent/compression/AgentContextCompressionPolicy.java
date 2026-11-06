/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.compression;

import com.agentsflex.core.message.Message;

import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Agent 的统一上下文压缩策略。
 *
 * <p>即时策略只在构建模型窗口时调用压缩器；增量策略由 Runner 在带会话 ID 的请求入口
 * 自动调用处理器，并通过状态 Store 保存游标和摘要。</p>
 */
public final class AgentContextCompressionPolicy {
    /**
     * 生成摘要消息的压缩器；默认策略下可为空。
     */
    private final AgentContextCompressor compressor;
    /**
     * 持久化增量压缩处理器；未配置状态 Store 时为空。
     */
    private final AgentContextCompressionProcessor processor;
    /**
     * 构建模型窗口时是否把已完成 ToolCall/ToolMessage 链归并为紧凑表示。
     */
    private final boolean compactCompletedToolTurns;
    /**
     * 每次压缩时原样保护的最近完整 Turn 数量。
     */
    private final int keepRecentTurns;
    /**
     * 压缩器异常时失败或回退原始消息的策略。
     */
    private final AgentCompressionFailureStrategy compressionFailureStrategy;

    /**
     * 校验并装配压缩策略。
     *
     * <p>配置 stateStore + compressor 即形成“持久化但手动触发”的策略；再同时配置 decider 和
     * tokenEstimator 后形成自动增量策略。decider 与 tokenEstimator 必须成对出现。</p>
     */
    private AgentContextCompressionPolicy(Builder builder) {
        boolean hasIncrementalDependency = builder.stateStore != null
            || builder.decider != null || builder.tokenEstimator != null;
        if (hasIncrementalDependency && (builder.stateStore == null || builder.compressor == null)) {
            throw new IllegalArgumentException(
                "persistent compression requires stateStore and compressor");
        }
        if ((builder.decider == null) != (builder.tokenEstimator == null)) {
            throw new IllegalArgumentException(
                "automatic compression requires both decider and tokenEstimator");
        }
        this.compressor = builder.compressor;
        this.processor = hasIncrementalDependency
            ? new AgentContextCompressionProcessor(
            builder.stateStore, builder.decider, builder.compressor, builder.tokenEstimator,
            builder.compressionFailureStrategy)
            : null;
        this.compactCompletedToolTurns = builder.compactCompletedToolTurns;
        this.keepRecentTurns = builder.keepRecentTurns;
        this.compressionFailureStrategy = builder.compressionFailureStrategy;
    }

    /**
     * 返回不调用语义摘要模型、仅执行工具 Turn 归一化的默认策略。
     */
    public static AgentContextCompressionPolicy defaults() {
        return builder().build();
    }

    /**
     * 创建无持久化状态的即时压缩策略。
     */
    public static AgentContextCompressionPolicy immediate(AgentContextCompressor compressor) {
        return builder().compressor(compressor).build();
    }

    /**
     * 创建增量策略。处理器由策略内部装配，Agent 运行时无需直接接触处理器。
     */
    public static AgentContextCompressionPolicy incremental(
        AgentContextCompressionStateStore store,
        AgentContextCompressionDecider decider,
        AgentContextCompressor compressor,
        ToLongFunction<List<Message>> tokenEstimator) {
        return builder()
            .stateStore(store)
            .decider(decider)
            .compressor(compressor)
            .tokenEstimator(tokenEstimator)
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回即时压缩器。持久化策略必须通过 processor 调用压缩器，因此这里返回 {@code null}，
     * 防止模型窗口逻辑绕过游标和 CAS 状态直接重复压缩。
     */
    public AgentContextCompressor getCompressor() {
        // 持久化策略的压缩器只能由 processor 调用，以维护摘要游标和版本一致性。
        return processor == null ? compressor : null;
    }

    /**
     * @return 是否配置了可持久化摘要和游标的压缩处理器
     */
    public boolean isIncremental() {
        return processor != null;
    }

    /**
     * 判断当前持久化策略是否带有自动压缩决策能力。
     *
     * <p>仅配置 stateStore + compressor 时返回 false，但仍可由服务端 Usage 或异常强制触发压缩。</p>
     */
    public boolean isAutomatic() {
        return processor != null && processor.isAutomatic();
    }

    /**
     * 执行一次增量压缩；仅供 Runner 使用，普通业务代码只需配置策略。
     */
    public AgentContextCompressionResult compress(String conversationId, List<Message> chronologicalMessages) {
        return compress(conversationId, chronologicalMessages, null);
    }

    /**
     * 执行一次增量压缩，并在真正调用压缩器前触发开始回调；未达到决策条件时不会触发回调。
     *
     * @param conversationId        会话 ID
     * @param chronologicalMessages 按时间升序排列的历史消息
     * @param onCompressionStarted  真正开始调用压缩器前执行的回调，可为空
     * @return 压缩结果
     */
    public AgentContextCompressionResult compress(String conversationId,
                                                  List<Message> chronologicalMessages,
                                                  Runnable onCompressionStarted) {
        if (processor == null) {
            throw new IllegalStateException("compression policy is not incremental");
        }
        return processor.process(conversationId, chronologicalMessages, onCompressionStarted);
    }

    /**
     * 强制执行持久化压缩，不咨询决策器，也不进行本地 Token 估算。
     *
     * <p>用于服务端 Usage 达阈值或模型返回上下文超限异常的场景。</p>
     */
    public AgentContextCompressionResult forceCompress(String conversationId,
                                                       List<Message> chronologicalMessages,
                                                       Runnable onCompressionStarted) {
        if (processor == null) {
            throw new IllegalStateException("forced compression requires an incremental compression policy");
        }
        return processor.force(conversationId, chronologicalMessages, onCompressionStarted);
    }

    /**
     * 读取当前持久化摘要，并拼接游标之后尚未压缩的原始消息，不执行新的压缩判断。
     *
     * <p>Usage 策略启用时，会话入口使用该方法恢复模型视图，等实际模型确定后再决定是否压缩。</p>
     */
    public AgentContextCompressionResult loadView(String conversationId,
                                                  List<Message> chronologicalMessages) {
        if (processor == null) {
            throw new IllegalStateException("compression policy is not persistent");
        }
        return processor.loadView(conversationId, chronologicalMessages);
    }

    /**
     * @return 是否压缩已完成的工具协议链
     */
    public boolean isCompactCompletedToolTurns() {
        return compactCompletedToolTurns;
    }

    /**
     * @return 每次压缩时保护的最近完整 Turn 数量
     */
    public int getKeepRecentTurns() {
        return keepRecentTurns;
    }

    /**
     * @return 压缩器异常处理策略
     */
    public AgentCompressionFailureStrategy getCompressionFailureStrategy() {
        return compressionFailureStrategy;
    }

    /**
     * 上下文压缩策略构建器。
     */
    public static final class Builder {
        /**
         * 摘要生成实现。
         */
        private AgentContextCompressor compressor;
        /**
         * 按会话持久化摘要和游标的 CAS Store。
         */
        private AgentContextCompressionStateStore stateStore;
        /**
         * 自动压缩决策器；必须与 tokenEstimator 同时配置。
         */
        private AgentContextCompressionDecider decider;
        /**
         * 自动决策使用的本地估算器；仅属于旧自动策略，不参与服务端 Usage 基准。
         */
        private ToLongFunction<List<Message>> tokenEstimator;
        /**
         * 默认紧凑化已完成的工具 Turn。
         */
        private boolean compactCompletedToolTurns = true;
        /**
         * 默认保护最近两个完整 Turn。
         */
        private int keepRecentTurns = 2;
        /**
         * 默认压缩失败即失败，不静默回退。
         */
        private AgentCompressionFailureStrategy compressionFailureStrategy = AgentCompressionFailureStrategy.FAIL;

        /**
         * 设置摘要压缩器。
         */
        public Builder compressor(AgentContextCompressor compressor) {
            this.compressor = compressor;
            return this;
        }

        /**
         * 设置持久化压缩状态 Store。
         */
        public Builder stateStore(AgentContextCompressionStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * 设置自动压缩决策器。
         */
        public Builder decider(AgentContextCompressionDecider decider) {
            this.decider = decider;
            return this;
        }

        /**
         * 设置旧自动压缩策略使用的本地 Token 估算器。
         */
        public Builder tokenEstimator(ToLongFunction<List<Message>> tokenEstimator) {
            this.tokenEstimator = tokenEstimator;
            return this;
        }

        /**
         * 设置是否紧凑化已完成的工具协议链。
         */
        public Builder compactCompletedToolTurns(boolean value) {
            this.compactCompletedToolTurns = value;
            return this;
        }

        /**
         * 设置压缩时原样保留的最近 Turn 数量。
         */
        public Builder keepRecentTurns(int value) {
            if (value < 0) throw new IllegalArgumentException("keepRecentTurns must not be negative");
            this.keepRecentTurns = value;
            return this;
        }

        /**
         * 设置即时压缩失败后的处理方式。增量策略仍以状态一致性为优先。
         */
        public Builder compressionFailureStrategy(AgentCompressionFailureStrategy value) {
            this.compressionFailureStrategy = value == null
                ? AgentCompressionFailureStrategy.FAIL : value;
            return this;
        }

        /**
         * 校验并创建不可变压缩策略。
         */
        public AgentContextCompressionPolicy build() {
            return new AgentContextCompressionPolicy(this);
        }
    }

}

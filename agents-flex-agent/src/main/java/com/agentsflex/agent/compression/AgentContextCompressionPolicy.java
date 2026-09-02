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
    private final AgentContextCompressor compressor;
    private final AgentContextCompressionProcessor processor;
    private final boolean compactCompletedToolTurns;
    private final int keepRecentTurns;
    private final AgentCompressionFailureStrategy compressionFailureStrategy;

    private AgentContextCompressionPolicy(Builder builder) {
        boolean hasIncrementalDependency = builder.stateStore != null
            || builder.decider != null || builder.tokenEstimator != null;
        if (hasIncrementalDependency && (builder.stateStore == null
            || builder.decider == null || builder.tokenEstimator == null || builder.compressor == null)) {
            throw new IllegalArgumentException(
                "incremental compression requires stateStore, decider, compressor and tokenEstimator");
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

    public AgentContextCompressor getCompressor() {
        // Incremental policies invoke their compressor only through the processor.
        return processor == null ? compressor : null;
    }

    public boolean isIncremental() {
        return processor != null;
    }

    /**
     * 执行一次增量压缩；仅供 Runner 使用，普通业务代码只需配置策略。
     */
    public AgentContextCompressionResult compress(String conversationId, List<Message> chronologicalMessages) {
        if (processor == null) {
            throw new IllegalStateException("compression policy is not incremental");
        }
        return processor.process(conversationId, chronologicalMessages);
    }

    public boolean isCompactCompletedToolTurns() {
        return compactCompletedToolTurns;
    }

    public int getKeepRecentTurns() {
        return keepRecentTurns;
    }

    public AgentCompressionFailureStrategy getCompressionFailureStrategy() {
        return compressionFailureStrategy;
    }

    public static final class Builder {
        private AgentContextCompressor compressor;
        private AgentContextCompressionStateStore stateStore;
        private AgentContextCompressionDecider decider;
        private ToLongFunction<List<Message>> tokenEstimator;
        private boolean compactCompletedToolTurns = true;
        private int keepRecentTurns = 2;
        private AgentCompressionFailureStrategy compressionFailureStrategy = AgentCompressionFailureStrategy.FAIL;

        public Builder compressor(AgentContextCompressor compressor) {
            this.compressor = compressor;
            return this;
        }

        public Builder stateStore(AgentContextCompressionStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        public Builder decider(AgentContextCompressionDecider decider) {
            this.decider = decider;
            return this;
        }

        public Builder tokenEstimator(ToLongFunction<List<Message>> tokenEstimator) {
            this.tokenEstimator = tokenEstimator;
            return this;
        }

        public Builder compactCompletedToolTurns(boolean value) {
            this.compactCompletedToolTurns = value;
            return this;
        }

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

        public AgentContextCompressionPolicy build() {
            return new AgentContextCompressionPolicy(this);
        }
    }

}

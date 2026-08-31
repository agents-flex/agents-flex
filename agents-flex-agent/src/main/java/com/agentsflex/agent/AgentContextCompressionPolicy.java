/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.message.Message;

import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Agent 的统一上下文压缩策略。
 *
 * <p>即时策略只在构建模型窗口时调用压缩器；增量策略由 Runner 在带会话 ID 的请求入口
 * 自动调用协调器，并通过状态 Store 保存游标和摘要。</p>
 */
public final class AgentContextCompressionPolicy {
    private final AgentContextCompressor compressor;
    private final AgentContextCompressionCoordinator coordinator;
    private final boolean compactCompletedToolTurns;
    private final int keepRecentTurns;

    private AgentContextCompressionPolicy(Builder builder) {
        if (builder.coordinator != null && builder.compressor != null) {
            throw new IllegalArgumentException("compression policy cannot configure both coordinator and compressor");
        }
        this.compressor = builder.compressor;
        this.coordinator = builder.coordinator;
        this.compactCompletedToolTurns = builder.compactCompletedToolTurns;
        this.keepRecentTurns = builder.keepRecentTurns;
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
     * 创建由协调器驱动的增量压缩策略。
     */
    public static AgentContextCompressionPolicy incremental(
        AgentContextCompressionCoordinator coordinator) {
        return builder().coordinator(coordinator).build();
    }

    /**
     * 创建增量策略并在策略内部装配协调器。
     */
    public static AgentContextCompressionPolicy incremental(
        AgentContextCompressionStateStore store,
        AgentContextCompressionTrigger trigger,
        AgentContextCompressor compressor,
        ToLongFunction<List<Message>> tokenEstimator) {
        return incremental(new AgentContextCompressionCoordinator(
            store, trigger, compressor, tokenEstimator));
    }

    public static Builder builder() {
        return new Builder();
    }

    public AgentContextCompressor getCompressor() {
        return compressor;
    }

    public AgentContextCompressionCoordinator getCoordinator() {
        return coordinator;
    }

    public boolean isIncremental() {
        return coordinator != null;
    }

    public boolean isCompactCompletedToolTurns() {
        return compactCompletedToolTurns;
    }

    public int getKeepRecentTurns() {
        return keepRecentTurns;
    }

    public static final class Builder {
        private AgentContextCompressor compressor;
        private AgentContextCompressionCoordinator coordinator;
        private boolean compactCompletedToolTurns = true;
        private int keepRecentTurns = 2;

        public Builder compressor(AgentContextCompressor compressor) {
            this.compressor = compressor;
            return this;
        }

        public Builder coordinator(AgentContextCompressionCoordinator coordinator) {
            this.coordinator = coordinator;
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

        public AgentContextCompressionPolicy build() {
            return new AgentContextCompressionPolicy(this);
        }
    }
}

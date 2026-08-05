/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 创建单次 AgentTurn 时使用的可选参数。
 *
 * <p>平台可以为不同任务使用推荐的迭代次数和预算，而不需要为每个任务重新创建 Agent 定义。
 * 解析后的执行策略会随 Snapshot 保存，保证任务恢复后继续使用启动时的限制。</p>
 */
public final class AgentTurnOptions {

    /**
     * 覆盖 Agent 默认值的单次运行执行策略；为空时使用 Agent 策略。
     */
    private final AgentExecutionPolicy executionPolicy;
    /**
     * 随 Snapshot 持久化的任务类型、账号等业务数据。
     */
    private final Map<String, Object> metadata;
    /**
     * 是否在当前进程中使用流式模型调用；该选项不写入 Snapshot。
     */
    private final boolean streaming;

    private AgentTurnOptions(Builder builder) {
        this.executionPolicy = builder.executionPolicy;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.streaming = builder.streaming;
    }

    /**
     * @return 不覆盖 Agent 策略且不附加元数据的默认选项
     */
    public static AgentTurnOptions defaults() {
        return builder().build();
    }

    /**
     * @return 新的单次运行选项构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 单次运行策略覆盖；未配置时为 {@code null}
     */
    public AgentExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    /**
     * @return 不可修改的持久化业务元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * @return 当前进程是否使用流式模型调用
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * 单次运行选项构建器。
     */
    public static final class Builder {
        private AgentExecutionPolicy executionPolicy;
        private final Map<String, Object> metadata = new HashMap<>();
        private boolean streaming;

        /**
         * 覆盖 Agent 定义中的默认执行策略。
         */
        public Builder executionPolicy(AgentExecutionPolicy value) {
            this.executionPolicy = value;
            return this;
        }

        /**
         * 添加账号、模块、任务类型、配置版本等运行元数据。
         */
        public Builder metadata(String key, Object value) {
            if (key == null) {
                throw new IllegalArgumentException("metadata key must not be null");
            }
            metadata.put(key, value);
            return this;
        }

        /**
         * 批量添加运行元数据。
         */
        public Builder metadata(Map<String, ?> values) {
            if (values != null) {
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    metadata(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        /**
         * 设置当前进程是否使用流式模型调用。
         *
         * <p>该值不写入 Snapshot。Worker 从 Snapshot 恢复任务时默认使用非流式调用。</p>
         */
        public Builder streaming(boolean value) {
            this.streaming = value;
            return this;
        }

        /**
         * 创建不可变运行选项。
         */
        public AgentTurnOptions build() {
            return new AgentTurnOptions(this);
        }
    }
}

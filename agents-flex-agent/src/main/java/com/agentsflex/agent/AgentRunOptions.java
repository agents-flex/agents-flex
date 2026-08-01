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
 * 创建单次 AgentRun 时使用的可选参数。
 *
 * <p>平台可以为不同任务使用推荐的迭代次数和预算，而不需要为每个任务重新创建 Agent 定义。
 * 解析后的执行策略会随 Checkpoint 保存，保证任务恢复后继续使用启动时的限制。</p>
 */
public final class AgentRunOptions {

    /** 覆盖 Agent 默认值的单次运行执行策略；为空时使用 Agent 策略。 */
    private final AgentExecutionPolicy executionPolicy;
    /** 随 Checkpoint 持久化的任务类型、账号等业务数据。 */
    private final Map<String, Object> metadata;
    /** 仅在当前进程传递且不会写入 Checkpoint 的调用上下文。 */
    private final AgentInvocationContext invocationContext;

    private AgentRunOptions(Builder builder) {
        this.executionPolicy = builder.executionPolicy;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.invocationContext = builder.invocationContext == null
            ? AgentInvocationContext.empty() : builder.invocationContext;
    }

    /** @return 不覆盖 Agent 策略且不附加元数据的默认选项 */
    public static AgentRunOptions defaults() {
        return builder().build();
    }

    /** @return 新的单次运行选项构建器 */
    public static Builder builder() {
        return new Builder();
    }

    /** @return 单次运行策略覆盖；未配置时为 {@code null} */
    public AgentExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    /** @return 不可修改的持久化业务元数据 */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** @return 非持久化调用上下文，始终不为 {@code null} */
    public AgentInvocationContext getInvocationContext() { return invocationContext; }

    /** 单次运行选项构建器。 */
    public static final class Builder {
        private AgentExecutionPolicy executionPolicy;
        private final Map<String, Object> metadata = new HashMap<>();
        private AgentInvocationContext invocationContext;

        /** 覆盖 Agent 定义中的默认执行策略。 */
        public Builder executionPolicy(AgentExecutionPolicy value) {
            this.executionPolicy = value;
            return this;
        }

        /** 添加账号、模块、任务类型、配置版本等运行元数据。 */
        public Builder metadata(String key, Object value) {
            if (key == null) {
                throw new IllegalArgumentException("metadata key must not be null");
            }
            metadata.put(key, value);
            return this;
        }

        /** 批量添加运行元数据。 */
        public Builder metadata(Map<String, ?> values) {
            if (values != null) {
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    metadata(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        /** 设置仅在本次进程内调用期间生效的运行上下文。 */
        public Builder invocationContext(AgentInvocationContext value) {
            this.invocationContext = value;
            return this;
        }

        /** 创建不可变运行选项。 */
        public AgentRunOptions build() {
            return new AgentRunOptions(this);
        }
    }
}

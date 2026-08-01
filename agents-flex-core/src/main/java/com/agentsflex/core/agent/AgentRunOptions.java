/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

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

    private final AgentExecutionPolicy executionPolicy;
    private final Map<String, Object> metadata;
    private final AgentInvocationContext invocationContext;

    private AgentRunOptions(Builder builder) {
        this.executionPolicy = builder.executionPolicy;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.invocationContext = builder.invocationContext == null
            ? AgentInvocationContext.empty() : builder.invocationContext;
    }

    public static AgentRunOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public AgentExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public AgentInvocationContext getInvocationContext() { return invocationContext; }

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

        public AgentRunOptions build() {
            return new AgentRunOptions(this);
        }
    }
}

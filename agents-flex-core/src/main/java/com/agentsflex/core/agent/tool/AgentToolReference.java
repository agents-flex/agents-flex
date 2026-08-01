/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.tool;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AgentRun 跨进程恢复工具时使用的不可变持久化引用。
 *
 * <p>Agent 和工具名称构成默认绑定身份；bindingId、bindingVersion 和 metadata 可由工具注册表补充，
 * 用于定位 MCP、远程工具目录或独立版本的工具实现。metadata 是工具定义元数据在创建引用时的快照，
 * 其中只能保存目标 Store 能够序列化的非敏感数据。</p>
 */
public final class AgentToolReference implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String agentId;
    private final String agentVersion;
    private final String toolName;
    private final String bindingId;
    private final String bindingVersion;
    private final Map<String, Object> metadata;

    private AgentToolReference(Builder builder) {
        if (!StringUtil.hasText(builder.agentId)
            || !StringUtil.hasText(builder.agentVersion)
            || !StringUtil.hasText(builder.toolName)) {
            throw new IllegalStateException(
                "agentId, agentVersion and toolName must not be blank");
        }
        this.agentId = builder.agentId;
        this.agentVersion = builder.agentVersion;
        this.toolName = builder.toolName;
        this.bindingId = builder.bindingId;
        this.bindingVersion = builder.bindingVersion;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    public static Builder builder(String agentId, String agentVersion, String toolName) {
        return new Builder(agentId, agentVersion, toolName);
    }

    public Builder toBuilder() {
        return new Builder(agentId, agentVersion, toolName)
            .bindingId(bindingId)
            .bindingVersion(bindingVersion)
            .metadata(metadata);
    }

    public String getAgentId() { return agentId; }

    public String getAgentVersion() { return agentVersion; }

    public String getToolName() { return toolName; }

    public String getBindingId() { return bindingId; }

    public String getBindingVersion() { return bindingVersion; }

    public Map<String, Object> getMetadata() { return metadata; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof AgentToolReference)) return false;
        AgentToolReference that = (AgentToolReference) value;
        return Objects.equals(agentId, that.agentId)
            && Objects.equals(agentVersion, that.agentVersion)
            && Objects.equals(toolName, that.toolName)
            && Objects.equals(bindingId, that.bindingId)
            && Objects.equals(bindingVersion, that.bindingVersion)
            && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentId, agentVersion, toolName, bindingId, bindingVersion, metadata);
    }

    @Override
    public String toString() {
        return "AgentToolReference{" +
            "agentId='" + agentId + '\'' +
            ", agentVersion='" + agentVersion + '\'' +
            ", toolName='" + toolName + '\'' +
            ", bindingId='" + bindingId + '\'' +
            ", bindingVersion='" + bindingVersion + '\'' +
            ", metadata=" + metadata +
            '}';
    }

    /** 构建工具持久化引用。 */
    public static final class Builder {
        private final String agentId;
        private final String agentVersion;
        private final String toolName;
        private String bindingId;
        private String bindingVersion;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String agentId, String agentVersion, String toolName) {
            this.agentId = agentId;
            this.agentVersion = agentVersion;
            this.toolName = toolName;
        }

        public Builder bindingId(String value) { this.bindingId = value; return this; }

        public Builder bindingVersion(String value) { this.bindingVersion = value; return this; }

        public Builder metadata(String key, Object value) {
            if (key == null) {
                throw new IllegalArgumentException("metadata key must not be null");
            }
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, ?> values) {
            if (values != null) {
                this.metadata.putAll(values);
            }
            return this;
        }

        public AgentToolReference build() { return new AgentToolReference(this); }
    }
}

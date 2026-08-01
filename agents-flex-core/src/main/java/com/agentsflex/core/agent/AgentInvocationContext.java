/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 Agent 调用使用的非持久化上下文。
 *
 * <p>该对象适合携带租户、用户、请求标识以及进程内服务对象。它会传递给 Middleware、模型调用
 * 和工具调用，但不会写入 AgentRunSnapshot。任务从 Checkpoint 恢复后，应由调用方重新附加上下文。</p>
 */
public final class AgentInvocationContext {

    public static final String CONTEXT_ATTRIBUTE = AgentInvocationContext.class.getName();

    private static final AgentInvocationContext EMPTY = builder().build();

    private final String tenantId;
    private final String userId;
    private final String sessionId;
    private final String requestId;
    private final boolean streaming;
    private final Map<String, Object> attributes;
    private final Map<Class<?>, Object> typedAttributes;

    private AgentInvocationContext(Builder builder) {
        this.tenantId = builder.tenantId;
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.requestId = builder.requestId;
        this.streaming = builder.streaming;
        this.attributes = new ConcurrentHashMap<>(builder.attributes);
        this.typedAttributes = new ConcurrentHashMap<>(builder.typedAttributes);
    }

    public static AgentInvocationContext empty() { return EMPTY; }

    public static Builder builder() { return new Builder(); }

    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getRequestId() { return requestId; }
    public boolean isStreaming() { return streaming; }

    public Object getAttribute(String key) { return attributes.get(key); }

    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    public <T> T get(Class<T> type) {
        Object value = typedAttributes.get(type);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static final class Builder {
        private String tenantId;
        private String userId;
        private String sessionId;
        private String requestId;
        private boolean streaming;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final Map<Class<?>, Object> typedAttributes = new LinkedHashMap<>();

        public Builder tenantId(String value) { tenantId = value; return this; }
        public Builder userId(String value) { userId = value; return this; }
        public Builder sessionId(String value) { sessionId = value; return this; }
        public Builder requestId(String value) { requestId = value; return this; }
        public Builder streaming(boolean value) { streaming = value; return this; }

        public Builder attribute(String key, Object value) {
            if (key == null || value == null) {
                throw new IllegalArgumentException("attribute key and value must not be null");
            }
            attributes.put(key, value);
            return this;
        }

        public <T> Builder attribute(Class<T> type, T value) {
            if (type == null || value == null || !type.isInstance(value)) {
                throw new IllegalArgumentException("typed attribute does not match its type");
            }
            typedAttributes.put(type, value);
            return this;
        }

        public AgentInvocationContext build() { return new AgentInvocationContext(this); }
    }
}

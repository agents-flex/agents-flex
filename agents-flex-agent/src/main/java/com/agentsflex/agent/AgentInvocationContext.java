/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 Agent 调用使用的非持久化上下文。
 *
 * <p>该对象适合携带租户、用户、请求标识以及进程内服务对象。它会传递给 Middleware、模型调用
 * 和工具调用，但不会写入 AgentRunSnapshot。任务从 Snapshot 恢复后，应由调用方重新附加上下文。</p>
 */
public final class AgentInvocationContext {

    /**
     * Runner 写入工具上下文时使用的稳定属性键。
     */
    public static final String CONTEXT_ATTRIBUTE = AgentInvocationContext.class.getName();

    private static final AgentInvocationContext EMPTY = builder().build();

    /**
     * 当前调用所属租户；单租户应用可以为空。
     */
    private final String tenantId;
    /**
     * 发起调用的业务用户 ID。
     */
    private final String userId;
    /**
     * 上层对话或业务会话 ID。
     */
    private final String sessionId;
    /**
     * 单次入口请求 ID，通常用于日志和 Trace 关联。
     */
    private final String requestId;
    /**
     * 调用方是否期望消费实时流式事件。
     */
    private final boolean streaming;
    /**
     * 按字符串键保存的线程安全运行时属性。
     */
    private final Map<String, Object> attributes;
    /**
     * 按 Java 类型保存的线程安全服务对象。
     */
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

    /**
     * @return 不携带任何调用身份或服务对象的共享上下文
     */
    public static AgentInvocationContext empty() {
        return EMPTY;
    }

    /**
     * @return 新的调用上下文构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 当前租户 ID
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * @return 当前业务用户 ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * @return 上层会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * @return 单次入口请求 ID
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * @return 调用方是否期望实时流式事件
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * 按字符串键读取运行时属性；不存在时返回 {@code null}。
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * 按字符串键读取并安全转换属性类型；类型不匹配时返回 {@code null}。
     */
    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /**
     * 按类型读取服务对象；不存在或类型不匹配时返回 {@code null}。
     */
    public <T> T get(Class<T> type) {
        Object value = typedAttributes.get(type);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /**
     * @return 当前字符串属性的不可修改快照
     */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * 非持久化调用上下文构建器。
     */
    public static final class Builder {
        private String tenantId;
        private String userId;
        private String sessionId;
        private String requestId;
        private boolean streaming;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final Map<Class<?>, Object> typedAttributes = new LinkedHashMap<>();

        /**
         * 设置租户 ID。
         */
        public Builder tenantId(String value) {
            tenantId = value;
            return this;
        }

        /**
         * 设置业务用户 ID。
         */
        public Builder userId(String value) {
            userId = value;
            return this;
        }

        /**
         * 设置上层会话 ID。
         */
        public Builder sessionId(String value) {
            sessionId = value;
            return this;
        }

        /**
         * 设置单次入口请求 ID。
         */
        public Builder requestId(String value) {
            requestId = value;
            return this;
        }

        /**
         * 设置调用方是否期望流式事件。
         */
        public Builder streaming(boolean value) {
            streaming = value;
            return this;
        }

        /**
         * 添加一个按字符串键访问的运行时属性。
         */
        public Builder attribute(String key, Object value) {
            if (key == null || value == null) {
                throw new IllegalArgumentException("attribute key and value must not be null");
            }
            attributes.put(key, value);
            return this;
        }

        /**
         * 添加一个按类型访问的进程内服务对象。
         */
        public <T> Builder attribute(Class<T> type, T value) {
            if (type == null || value == null || !type.isInstance(value)) {
                throw new IllegalArgumentException("typed attribute does not match its type");
            }
            typedAttributes.put(type, value);
            return this;
        }

        /**
         * 创建线程安全的调用上下文。
         */
        public AgentInvocationContext build() {
            return new AgentInvocationContext(this);
        }
    }
}

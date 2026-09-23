package com.agentsflex.graph.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 Graph SDK 操作的可追踪上下文。
 *
 * <p>SDK 不解释租户、请求或 Schema 版本的业务含义，只负责在适配器、日志和回调之间
 * 传递这些关联信息，便于开发者构建自己的控制面和审计链路。</p>
 */
public final class GraphExecutionContext {
    /**
     * 连接注册表中的逻辑连接名称。
     */
    private final String connectionName;
    /**
     * 调用方租户标识；SDK 不负责授权校验。
     */
    private final String tenantId;
    /**
     * 请求或调用链标识，用于日志和审计关联。
     */
    private final String requestId;
    /**
     * 调用方期望使用的 Schema 版本。
     */
    private final String schemaVersion;
    /**
     * 调用方自定义的只读上下文属性。
     */
    private final Map<String, String> attributes;

    /**
     * 根据构造器内容创建不可变上下文快照。
     */
    private GraphExecutionContext(Builder builder) {
        this.connectionName = text(builder.connectionName);
        this.tenantId = text(builder.tenantId);
        this.requestId = text(builder.requestId);
        this.schemaVersion = text(builder.schemaVersion);
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    /**
     * 将可选文本字段统一归一化为空字符串。
     */
    private static String text(String value) {
        return value == null || value.trim().isEmpty() ? "" : value;
    }

    /**
     * @return 连接注册表中的逻辑连接名。
     */
    public String getConnectionName() {
        return connectionName;
    }

    /**
     * @return 租户标识；SDK 不对其进行授权判断。
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * @return 请求或调用链标识。
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * @return 调用方使用的 Schema 版本。
     */
    public String getSchemaVersion() {
        return schemaVersion;
    }

    /**
     * @return 调用方附加的只读上下文属性。
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * @return 新的上下文构造器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Graph 操作上下文构造器。
     */
    public static final class Builder {
        /**
         * 待绑定的连接名称。
         */
        private String connectionName;
        /**
         * 待绑定的租户标识。
         */
        private String tenantId;
        /**
         * 待绑定的请求标识。
         */
        private String requestId;
        /**
         * 待绑定的 Schema 版本。
         */
        private String schemaVersion;
        /**
         * 待绑定的扩展属性。
         */
        private final Map<String, String> attributes = new LinkedHashMap<>();

        /**
         * 设置逻辑连接名称。
         */
        public Builder connectionName(String value) {
            this.connectionName = value;
            return this;
        }

        /**
         * 设置租户标识；SDK 不执行授权判断。
         */
        public Builder tenantId(String value) {
            this.tenantId = value;
            return this;
        }

        /**
         * 设置请求或调用链标识。
         */
        public Builder requestId(String value) {
            this.requestId = value;
            return this;
        }

        /**
         * 设置 Schema 版本。
         */
        public Builder schemaVersion(String value) {
            this.schemaVersion = value;
            return this;
        }

        /**
         * 添加或覆盖一个扩展属性。
         */
        public Builder attribute(String name, String value) {
            if (name == null || name.trim().isEmpty())
                throw new IllegalArgumentException("attribute name must not be blank");
            attributes.put(name, value == null ? "" : value);
            return this;
        }

        /**
         * 批量添加扩展属性；传入 {@code null} 时不执行任何操作。
         */
        public Builder attributes(Map<String, String> values) {
            if (values != null)
                for (Map.Entry<String, String> entry : values.entrySet()) attribute(entry.getKey(), entry.getValue());
            return this;
        }

        /**
         * 构建不可变执行上下文。
         */
        public GraphExecutionContext build() {
            return new GraphExecutionContext(this);
        }
    }
}

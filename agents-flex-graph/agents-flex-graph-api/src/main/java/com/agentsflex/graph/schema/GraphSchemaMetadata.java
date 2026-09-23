package com.agentsflex.graph.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schema 版本和面向开发工具的描述元数据。
 *
 * <p>版本、显示名称和扩展属性用于上层版本管理、迁移审批和 Schema 设计工具，
 * 不会被适配器自动当作数据库字段或 DDL。</p>
 */
public final class GraphSchemaMetadata {
    /**
     * Schema 的稳定业务标识。
     */
    private final String id;
    /**
     * Schema 版本号或版本文本。
     */
    private final String version;
    /**
     * 面向用户展示的 Schema 名称。
     */
    private final String displayName;
    /**
     * 面向用户展示的 Schema 描述。
     */
    private final String description;
    /**
     * 供上层工具扩展的键值属性，只读快照。
     */
    private final Map<String, String> attributes;

    /**
     * 创建 Schema 元数据。
     *
     * @param id          稳定业务标识
     * @param version     版本文本
     * @param displayName 展示名称
     * @param description 展示描述
     * @param attributes  扩展属性，为 {@code null} 时按空映射处理
     */
    public GraphSchemaMetadata(String id, String version, String displayName, String description,
                               Map<String, String> attributes) {
        this.id = text(id);
        this.version = text(version);
        this.displayName = text(displayName);
        this.description = text(description);
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes == null
            ? Collections.<String, String>emptyMap() : attributes));
    }

    /**
     * @return 不包含任何版本和展示信息的空元数据。
     */
    public static GraphSchemaMetadata empty() {
        return new GraphSchemaMetadata("", "", "", "", null);
    }

    /**
     * 将可选文本字段统一归一化为空字符串。
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * @return Schema 稳定业务标识。
     */
    public String getId() {
        return id;
    }

    /**
     * @return Schema 版本文本。
     */
    public String getVersion() {
        return version;
    }

    /**
     * @return Schema 展示名称。
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return Schema 展示描述。
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return 只读扩展属性映射。
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }
}

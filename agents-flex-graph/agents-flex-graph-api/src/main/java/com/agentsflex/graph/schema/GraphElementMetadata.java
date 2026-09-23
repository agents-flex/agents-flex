package com.agentsflex.graph.schema;

/**
 * 节点类型或边类型的展示元数据。
 *
 * <p>该对象只服务于 Schema 描述和开发工具展示，不会被适配器强制编译为数据库 DDL。</p>
 */
public final class GraphElementMetadata {
    /**
     * 面向用户展示的名称。
     */
    private final String displayName;
    /**
     * 面向用户展示的描述文本。
     */
    private final String description;

    /**
     * 创建元素展示元数据。
     *
     * @param displayName 展示名称，为 {@code null} 时按空字符串处理
     * @param description 展示描述，为 {@code null} 时按空字符串处理
     */
    public GraphElementMetadata(String displayName, String description) {
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
    }

    /**
     * @return 不包含任何展示信息的空元数据。
     */
    public static GraphElementMetadata empty() {
        return new GraphElementMetadata("", "");
    }

    /**
     * @return 展示名称。
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return 展示描述。
     */
    public String getDescription() {
        return description;
    }
}

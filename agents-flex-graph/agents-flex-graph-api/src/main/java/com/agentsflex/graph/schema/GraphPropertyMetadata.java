package com.agentsflex.graph.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 属性在开发工具中的展示、默认值和枚举元数据。
 *
 * <p>默认值保留为 {@link Object}，因为不同数据库和业务模型可能使用字符串、数值或布尔值；
 * SDK 不会在元数据层强制转换其类型。</p>
 */
public final class GraphPropertyMetadata {
    /**
     * 属性展示名称。
     */
    private final String displayName;
    /**
     * 属性展示描述。
     */
    private final String description;
    /**
     * 属性默认值，可以为 {@code null}。
     */
    private final Object defaultValue;
    /**
     * 可选的枚举值列表，只读快照。
     */
    private final List<String> enumValues;

    /**
     * 创建属性展示元数据。
     *
     * @param displayName  展示名称
     * @param description  展示描述
     * @param defaultValue 默认值
     * @param enumValues   可选枚举值，{@code null} 表示没有枚举限制
     */
    public GraphPropertyMetadata(String displayName, String description, Object defaultValue,
                                 List<String> enumValues) {
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
        this.defaultValue = defaultValue;
        this.enumValues = Collections.unmodifiableList(new ArrayList<>(enumValues == null
            ? Collections.<String>emptyList() : enumValues));
    }

    /**
     * @return 不包含展示、默认值和枚举信息的空元数据。
     */
    public static GraphPropertyMetadata empty() {
        return new GraphPropertyMetadata("", "", null, null);
    }

    /**
     * @return 属性展示名称。
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return 属性展示描述。
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return 属性默认值。
     */
    public Object getDefaultValue() {
        return defaultValue;
    }

    /**
     * @return 只读枚举值列表。
     */
    public List<String> getEnumValues() {
        return enumValues;
    }
}

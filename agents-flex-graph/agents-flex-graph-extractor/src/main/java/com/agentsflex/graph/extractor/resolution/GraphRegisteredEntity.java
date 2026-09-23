package com.agentsflex.graph.extractor.resolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期知识库中已经确认身份的不可变实体注册记录。
 *
 * <p>注册记录把业务节点 ID、Schema 类型、规范名称和稳定别名关联起来。它不是图数据库节点的
 * 完整副本，而是跨文档、跨批次实体解析所需的最小索引信息。</p>
 */
public final class GraphRegisteredEntity {
    /**
     * 图数据库中的稳定节点 ID。
     */
    private final String nodeId;
    /**
     * 对应 GraphSchema.NodeType 的实体类型。
     */
    private final String type;
    /**
     * 人工或解析流程确认的规范名称。
     */
    private final String canonicalName;
    /**
     * 可用于后续批次匹配的稳定别名。
     */
    private final List<String> aliases;
    /**
     * 注册时保留的只读实体属性快照。
     */
    private final Map<String, Object> properties;

    /**
     * 创建实体注册记录。
     *
     * @param nodeId        图节点稳定 ID
     * @param type          Schema 节点类型
     * @param canonicalName 规范名称
     * @param aliases       稳定别名，可以为空
     * @param properties    已确认属性，可以为空
     */
    public GraphRegisteredEntity(String nodeId, String type, String canonicalName, List<String> aliases,
                                 Map<String, ?> properties) {
        this.nodeId = text(nodeId, "nodeId");
        this.type = text(type, "type");
        this.canonicalName = text(canonicalName, "canonicalName");
        List<String> aliasCopy = new ArrayList<>();
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.trim().isEmpty()) aliasCopy.add(alias.trim());
            }
        }
        this.aliases = Collections.unmodifiableList(aliasCopy);
        Map<String, Object> propertyCopy = new LinkedHashMap<>();
        if (properties != null) propertyCopy.putAll(properties);
        this.properties = Collections.unmodifiableMap(propertyCopy);
    }

    /**
     * @return 图数据库中的稳定节点 ID。
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * @return Schema 节点类型。
     */
    public String getType() {
        return type;
    }

    /**
     * @return 规范名称。
     */
    public String getCanonicalName() {
        return canonicalName;
    }

    /**
     * @return 不可变稳定别名列表。
     */
    public List<String> getAliases() {
        return aliases;
    }

    /**
     * @return 不可变属性快照。
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * 校验并裁剪必填文本。
     */
    private static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}

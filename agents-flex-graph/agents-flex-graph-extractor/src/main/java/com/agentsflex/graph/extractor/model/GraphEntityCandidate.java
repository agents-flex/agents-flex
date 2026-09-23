package com.agentsflex.graph.extractor.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型或其他抽取器从一个分段中识别出的不可变候选实体。
 *
 * <p>candidateKey 在一次流水线中全局唯一，用于局部关系端点引用和实体归一映射，但不是最终
 * 数据库 ID。属性、别名和证据在进入数据库前仍需经过 Schema 校验与调用方审核。</p>
 */
public final class GraphEntityCandidate {
    /**
     * 分段作用域内唯一的候选键。
     */
    private final String candidateKey;
    /**
     * 原文中最主要的实体名称。
     */
    private final String name;
    /**
     * 对应 GraphSchema.NodeType 的标签。
     */
    private final String type;
    /**
     * 当前分段中识别出的别名。
     */
    private final List<String> aliases;
    /**
     * 符合 Schema 的候选属性。
     */
    private final Map<String, Object> properties;
    /**
     * 支持该候选实体的原文证据。
     */
    private final GraphEvidence evidence;
    /**
     * 模型置信度，范围为 0 到 1。
     */
    private final double confidence;

    /**
     * 创建不可变候选实体，并防御性复制别名和属性。
     *
     * @param candidateKey 已包含 Chunk 作用域的唯一候选键
     * @param name         当前文本中识别出的实体主名称
     * @param type         对应 GraphSchema.NodeType 的标签
     * @param aliases      当前分段中出现的稳定别名
     * @param properties   待 Schema 校验的候选属性
     * @param evidence     支持实体存在的当前分段证据，可以为 {@code null}
     * @param confidence   0 到 1 的模型置信度
     */
    public GraphEntityCandidate(String candidateKey, String name, String type, List<String> aliases,
                                Map<String, ?> properties, GraphEvidence evidence, double confidence) {
        this.candidateKey = text(candidateKey, "candidateKey");
        this.name = text(name, "entity name");
        this.type = text(type, "entity type");
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)
            || confidence < 0D || confidence > 1D) {
            throw new IllegalArgumentException("confidence must be a finite number between 0 and 1");
        }
        List<String> aliasCopy = new ArrayList<>();
        if (aliases != null)
            for (String alias : aliases) if (alias != null && !alias.trim().isEmpty()) aliasCopy.add(alias.trim());
        this.aliases = Collections.unmodifiableList(aliasCopy);
        Map<String, Object> propertyCopy = new LinkedHashMap<>();
        if (properties != null) propertyCopy.putAll(properties);
        this.properties = Collections.unmodifiableMap(propertyCopy);
        this.evidence = evidence;
        this.confidence = confidence;
    }

    /**
     * @return 流水线内唯一候选键。
     */
    public String getCandidateKey() {
        return candidateKey;
    }

    /**
     * @return 实体名称。
     */
    public String getName() {
        return name;
    }

    /**
     * @return Schema 节点类型。
     */
    public String getType() {
        return type;
    }

    /**
     * @return 只读别名列表。
     */
    public List<String> getAliases() {
        return aliases;
    }

    /**
     * @return 只读候选属性。
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * @return 原文证据，可以为 null。
     */
    public GraphEvidence getEvidence() {
        return evidence;
    }

    /**
     * @return 模型置信度。
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * 校验必填文本并去除首尾空白。
     */
    private static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}

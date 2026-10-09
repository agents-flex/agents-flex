package com.agentsflex.graph.extractor.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型或其他抽取器识别出的不可变有向候选关系。
 *
 * <p>关系端点引用候选实体键而非数据库节点 ID。实体归一结束后，GraphCandidateMutationMapper 才会把
 * 两端转换为稳定节点 ID，并使用 type 与 rank 构造最终 GraphEdgeKey。</p>
 */
public final class GraphRelationCandidate {
    /**
     * 起点候选实体键。
     */
    private final String sourceCandidateKey;
    /**
     * 对应 GraphSchema.EdgeType 的关系类型。
     */
    private final String type;
    /**
     * 终点候选实体键。
     */
    private final String targetCandidateKey;
    /**
     * 平行边序号。
     */
    private final long rank;
    /**
     * 符合 Schema 的候选属性。
     */
    private final Map<String, Object> properties;
    /**
     * 支持关系的原文证据。
     */
    private final GraphEvidence evidence;
    /**
     * 模型置信度。
     */
    private final double confidence;
    /**
     * 关系是明确事实、推断还是观点。
     */
    private final GraphAssertionType assertionType;

    /**
     * 创建不可变候选关系，并防御性复制关系属性。
     *
     * @param sourceCandidateKey 起点候选实体键
     * @param type               对应 GraphSchema.EdgeType 的关系类型
     * @param targetCandidateKey 终点候选实体键
     * @param rank               同端点、同类型平行边的序号
     * @param properties         待 Schema 校验的候选属性
     * @param evidence           支持该关系的当前分段证据，可以为 {@code null}
     * @param confidence         0 到 1 的模型置信度
     * @param assertionType      明确事实、推断或观点；为 {@code null} 时默认为 EXPLICIT
     */
    public GraphRelationCandidate(String sourceCandidateKey, String type, String targetCandidateKey, long rank,
                                  Map<String, ?> properties, GraphEvidence evidence, double confidence,
                                  GraphAssertionType assertionType) {
        this.sourceCandidateKey = text(sourceCandidateKey, "sourceCandidateKey");
        this.type = text(type, "relation type");
        this.targetCandidateKey = text(targetCandidateKey, "targetCandidateKey");
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)
            || confidence < 0D || confidence > 1D) {
            throw new IllegalArgumentException("confidence must be a finite number between 0 and 1");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        if (properties != null) copy.putAll(properties);
        this.properties = Collections.unmodifiableMap(copy);
        this.rank = rank;
        this.evidence = evidence;
        this.confidence = confidence;
        this.assertionType = assertionType == null ? GraphAssertionType.EXPLICIT : assertionType;
    }

    /**
     * @return 起点候选实体键。
     */
    public String getSourceCandidateKey() {
        return sourceCandidateKey;
    }

    /**
     * @return 关系类型。
     */
    public String getType() {
        return type;
    }

    /**
     * @return 终点候选实体键。
     */
    public String getTargetCandidateKey() {
        return targetCandidateKey;
    }

    /**
     * @return 平行边 rank。
     */
    public long getRank() {
        return rank;
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
     * @return 断言类型。
     */
    public GraphAssertionType getAssertionType() {
        return assertionType;
    }

    /**
     * 校验必填文本并去除首尾空白。
     */
    private static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}

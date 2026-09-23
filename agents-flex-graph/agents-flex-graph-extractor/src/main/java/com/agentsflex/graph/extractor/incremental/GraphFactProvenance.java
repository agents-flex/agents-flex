package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEvidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个文档版本对某条物化关系提供的不可变事实来源记录。
 *
 * <p>同一 GraphEdgeKey 可以拥有多条来源记录，分别指向不同文档、Chunk 或原文证据。删除一个
 * 文档版本时，增量服务会先确认是否仍有其他文档支持该关系。</p>
 */
public final class GraphFactProvenance {
    /**
     * 区分来源事实声明与物化边的稳定事实 ID。
     */
    private final String factId;
    /**
     * 产生该事实的导入操作号。
     */
    private final String operationId;
    /**
     * 产生该事实的文档 revision。
     */
    private final long documentRevision;
    /**
     * 事实记录创建时间。
     */
    private final long createdAtMillis;
    /**
     * 被证据支持的物化关系身份。
     */
    private final GraphEdgeKey edgeKey;
    /**
     * 原始文档和 Chunk 中的证据定位。
     */
    private final GraphEvidence evidence;
    /**
     * 模型或规则产生的置信度。
     */
    private final double confidence;
    /**
     * 显式事实、推断或观点分类。
     */
    private final GraphAssertionType assertionType;
    /**
     * 抽取时关系属性的只读快照。
     */
    private final Map<String, Object> properties;

    /**
     * 创建事实来源记录。
     */
    public GraphFactProvenance(GraphEdgeKey edgeKey, GraphEvidence evidence, double confidence,
                               GraphAssertionType assertionType, Map<String, ?> properties) {
        this("", "", 0L, 0L, edgeKey, evidence, confidence, assertionType, properties);
    }

    /**
     * 创建包含完整运行身份的事实来源记录。
     */
    public GraphFactProvenance(String factId, String operationId, long documentRevision, long createdAtMillis,
                               GraphEdgeKey edgeKey, GraphEvidence evidence, double confidence,
                               GraphAssertionType assertionType, Map<String, ?> properties) {
        if (edgeKey == null || evidence == null || assertionType == null) {
            throw new IllegalArgumentException("edgeKey, evidence and assertionType must not be null");
        }
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)
            || confidence < 0D || confidence > 1D) {
            throw new IllegalArgumentException("confidence must be a finite number between 0 and 1");
        }
        if (documentRevision < 0L || createdAtMillis < 0L) {
            throw new IllegalArgumentException("documentRevision and createdAtMillis must not be negative");
        }
        this.factId = factId == null ? "" : factId.trim();
        this.operationId = operationId == null ? "" : operationId.trim();
        this.documentRevision = documentRevision;
        this.createdAtMillis = createdAtMillis;
        this.edgeKey = edgeKey;
        this.evidence = evidence;
        this.confidence = confidence;
        this.assertionType = assertionType;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (properties != null) copy.putAll(properties);
        this.properties = Collections.unmodifiableMap(copy);
    }

    /**
     * @return 稳定事实 ID；旧构造器创建的记录为空。
     */
    public String getFactId() {
        return factId;
    }

    /**
     * @return 产生事实的操作号。
     */
    public String getOperationId() {
        return operationId;
    }

    /**
     * @return 产生事实的文档 revision。
     */
    public long getDocumentRevision() {
        return documentRevision;
    }

    /**
     * @return 事实记录创建时间。
     */
    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    /**
     * @return 物化关系键。
     */
    public GraphEdgeKey getEdgeKey() {
        return edgeKey;
    }

    /**
     * @return 文档、Chunk、原文和偏移证据。
     */
    public GraphEvidence getEvidence() {
        return evidence;
    }

    /**
     * @return 置信度。
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
     * @return 抽取时关系属性快照。
     */
    public Map<String, Object> getProperties() {
        return properties;
    }
}

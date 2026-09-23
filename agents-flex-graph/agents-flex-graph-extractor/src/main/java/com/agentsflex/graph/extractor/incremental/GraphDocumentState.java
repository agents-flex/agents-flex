package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.graph.data.GraphEdgeKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 某个 Space 中一个逻辑文档当前已提交版本的不可变状态。
 *
 * <p>状态用于内容判重、版本替换和关系来源引用判断，不保存文档正文或模型原始响应。生产环境应
 * 通过 {@link GraphDocumentStateStore} 的持久化实现长期保存。</p>
 */
public final class GraphDocumentState {
    /**
     * 逻辑知识库或图空间。
     */
    private final String space;
    /**
     * 跨版本稳定的逻辑文档 ID。
     */
    private final String documentId;
    /**
     * 状态乐观锁版本，从 1 开始递增。
     */
    private final long revision;
    /**
     * 当前文档内容的 SHA-256 或调用方提供的稳定摘要。
     */
    private final String contentHash;
    /**
     * 调用方定义的业务文档版本。
     */
    private final String documentVersion;
    /**
     * 抽取时使用的 Schema 版本。
     */
    private final String schemaVersion;
    /**
     * 产生本状态的导入批次 ID。
     */
    private final String batchId;
    /**
     * 状态成功提交的 Unix 毫秒时间。
     */
    private final long committedAtMillis;
    /**
     * 当前文档版本产生的业务节点 ID。
     */
    private final Set<String> nodeIds;
    /**
     * 当前文档版本支持的物化关系键。
     */
    private final Set<GraphEdgeKey> edgeKeys;
    /**
     * 当前文档版本中每个关系候选的完整来源记录。
     */
    private final List<GraphFactProvenance> factProvenances;

    /**
     * 从构造器复制并冻结状态。
     */
    private GraphDocumentState(Builder builder) {
        space = text(builder.space, "space");
        documentId = text(builder.documentId, "documentId");
        if (builder.revision <= 0) throw new IllegalArgumentException("revision must be positive");
        revision = builder.revision;
        contentHash = text(builder.contentHash, "contentHash");
        documentVersion = optional(builder.documentVersion);
        schemaVersion = optional(builder.schemaVersion);
        batchId = optional(builder.batchId);
        if (builder.committedAtMillis < 0) throw new IllegalArgumentException("committedAtMillis must not be negative");
        committedAtMillis = builder.committedAtMillis;
        nodeIds = immutable(builder.nodeIds, "nodeIds");
        edgeKeys = immutable(builder.edgeKeys, "edgeKeys");
        List<GraphFactProvenance> provenanceCopy = new ArrayList<>(builder.factProvenances);
        if (provenanceCopy.contains(null)) {
            throw new IllegalArgumentException("factProvenances must not contain null elements");
        }
        factProvenances = Collections.unmodifiableList(provenanceCopy);
    }

    /**
     * @return 指定 Space 和文档 ID 的状态构造器。
     */
    public static Builder builder(String space, String documentId, String contentHash) {
        return new Builder(space, documentId, contentHash);
    }

    /**
     * @return 图空间。
     */
    public String getSpace() {
        return space;
    }

    /**
     * @return 逻辑文档 ID。
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * @return 乐观锁 revision。
     */
    public long getRevision() {
        return revision;
    }

    /**
     * @return 内容摘要。
     */
    public String getContentHash() {
        return contentHash;
    }

    /**
     * @return 业务文档版本。
     */
    public String getDocumentVersion() {
        return documentVersion;
    }

    /**
     * @return Schema 版本。
     */
    public String getSchemaVersion() {
        return schemaVersion;
    }

    /**
     * @return 导入批次 ID。
     */
    public String getBatchId() {
        return batchId;
    }

    /**
     * @return 状态提交时间。
     */
    public long getCommittedAtMillis() {
        return committedAtMillis;
    }

    /**
     * @return 当前版本产生的节点 ID 集合。
     */
    public Set<String> getNodeIds() {
        return nodeIds;
    }

    /**
     * @return 当前版本支持的关系键集合。
     */
    public Set<GraphEdgeKey> getEdgeKeys() {
        return edgeKeys;
    }

    /**
     * @return 当前版本按抽取顺序保存的事实来源记录。
     */
    public List<GraphFactProvenance> getFactProvenances() {
        return factProvenances;
    }

    /**
     * 创建拒绝 null 元素的不可变集合副本。
     */
    private static <T> Set<T> immutable(Set<T> source, String name) {
        Set<T> copy = new LinkedHashSet<>();
        if (source != null) copy.addAll(source);
        if (copy.contains(null)) throw new IllegalArgumentException(name + " must not contain null elements");
        return Collections.unmodifiableSet(copy);
    }

    /**
     * 校验必填文本。
     */
    private static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    /**
     * 统一裁剪可选文本。
     */
    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 文档状态构造器。
     */
    public static final class Builder {
        /**
         * 构造阶段的 Space。
         */
        private final String space;
        /**
         * 构造阶段的逻辑文档 ID。
         */
        private final String documentId;
        /**
         * 构造阶段的内容摘要。
         */
        private final String contentHash;
        /**
         * 下一状态 revision。
         */
        private long revision = 1L;
        /**
         * 业务版本。
         */
        private String documentVersion;
        /**
         * Schema 版本。
         */
        private String schemaVersion;
        /**
         * 导入批次。
         */
        private String batchId;
        /**
         * 提交时间。
         */
        private long committedAtMillis;
        /**
         * 节点 ID。
         */
        private final Set<String> nodeIds = new LinkedHashSet<>();
        /**
         * 关系键。
         */
        private final Set<GraphEdgeKey> edgeKeys = new LinkedHashSet<>();
        /**
         * 事实来源。
         */
        private final List<GraphFactProvenance> factProvenances = new ArrayList<>();

        private Builder(String space, String documentId, String contentHash) {
            this.space = space;
            this.documentId = documentId;
            this.contentHash = contentHash;
        }

        /**
         * 设置乐观锁 revision。
         */
        public Builder revision(long value) {
            revision = value;
            return this;
        }

        /**
         * 设置业务文档版本。
         */
        public Builder documentVersion(String value) {
            documentVersion = value;
            return this;
        }

        /**
         * 设置 Schema 版本。
         */
        public Builder schemaVersion(String value) {
            schemaVersion = value;
            return this;
        }

        /**
         * 设置导入批次 ID。
         */
        public Builder batchId(String value) {
            batchId = value;
            return this;
        }

        /**
         * 设置提交时间。
         */
        public Builder committedAtMillis(long value) {
            committedAtMillis = value;
            return this;
        }

        /**
         * 添加节点 ID。
         */
        public Builder nodeId(String value) {
            if (value != null) nodeIds.add(value);
            return this;
        }

        /**
         * 批量添加节点 ID。
         */
        public Builder nodeIds(java.util.Collection<String> values) {
            if (values != null) nodeIds.addAll(values);
            return this;
        }

        /**
         * 添加关系键。
         */
        public Builder edgeKey(GraphEdgeKey value) {
            if (value != null) edgeKeys.add(value);
            return this;
        }

        /**
         * 批量添加关系键。
         */
        public Builder edgeKeys(java.util.Collection<GraphEdgeKey> values) {
            if (values != null) edgeKeys.addAll(values);
            return this;
        }

        /**
         * 添加事实来源，并自动登记对应关系键。
         */
        public Builder factProvenance(GraphFactProvenance value) {
            if (value != null) {
                factProvenances.add(value);
                edgeKeys.add(value.getEdgeKey());
            }
            return this;
        }

        /**
         * 批量添加事实来源，并自动登记对应关系键。
         */
        public Builder factProvenances(java.util.Collection<GraphFactProvenance> values) {
            if (values != null) for (GraphFactProvenance value : values) factProvenance(value);
            return this;
        }

        /**
         * @return 校验并冻结后的文档状态。
         */
        public GraphDocumentState build() {
            return new GraphDocumentState(this);
        }
    }
}

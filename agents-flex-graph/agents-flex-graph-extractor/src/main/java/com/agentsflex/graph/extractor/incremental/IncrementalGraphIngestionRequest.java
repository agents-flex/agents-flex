package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.extractor.GraphExtractionOptions;
import com.agentsflex.graph.identifier.GraphIdentifiers;

/**
 * 一次长期增量文档抽取的不可变请求配置。
 */
public final class IncrementalGraphIngestionRequest {
    /**
     * 发现新版本不再包含旧关系时的处理策略。
     */
    public enum StaleRelationPolicy {
        /**
         * 保留旧物化关系，只在计划中报告其已经过期。
         */
        KEEP,
        /**
         * 仅当同一 Space 中没有其他文档引用时删除旧关系。
         */
        DELETE_IF_UNREFERENCED
    }

    /**
     * 目标知识库或图空间。
     */
    private final String space;
    /**
     * 跨版本稳定的逻辑文档 ID。
     */
    private final String documentId;
    /**
     * 可选的调用方内容摘要；为空时由 SDK 计算 SHA-256。
     */
    private final String contentHash;
    /**
     * 可选业务版本；为空时使用最终内容摘要。
     */
    private final String documentVersion;
    /**
     * 抽取所用 Schema 版本。
     */
    private final String schemaVersion;
    /**
     * 本次导入批次 ID。
     */
    private final String batchId;
    /**
     * 模型抽取质量和容错选项。
     */
    private final GraphExtractionOptions extractionOptions;
    /**
     * 图写入路由、超时和调用上下文。
     */
    private final GraphOptions graphOptions;
    /**
     * 旧关系处理策略。
     */
    private final StaleRelationPolicy staleRelationPolicy;
    /**
     * 抽取结果出现 ERROR 时是否禁止生成可执行计划。
     */
    private final boolean rejectExtractionErrors;
    /**
     * 内容未变化时是否仍然重新调用 extractor。
     */
    private final boolean forceReextract;

    /**
     * 从 Builder 复制配置并完成跨字段校验。
     */
    private IncrementalGraphIngestionRequest(Builder builder) {
        space = GraphIdentifiers.requireValid(builder.space, "space");
        documentId = text(builder.documentId, "documentId");
        contentHash = optional(builder.contentHash);
        documentVersion = optional(builder.documentVersion);
        schemaVersion = optional(builder.schemaVersion);
        batchId = optional(builder.batchId);
        extractionOptions = builder.extractionOptions == null ? GraphExtractionOptions.DEFAULT : builder.extractionOptions;
        graphOptions = builder.graphOptions == null ? GraphOptions.ofSpace(space) : builder.graphOptions;
        if (graphOptions.getSpace() == null || !space.equals(graphOptions.getSpace())) {
            throw new IllegalArgumentException("graphOptions must explicitly target request space");
        }
        staleRelationPolicy = builder.staleRelationPolicy == null
            ? StaleRelationPolicy.KEEP : builder.staleRelationPolicy;
        rejectExtractionErrors = builder.rejectExtractionErrors;
        forceReextract = builder.forceReextract;
    }

    /**
     * @return 指定 Space 和逻辑文档 ID 的请求构造器。
     */
    public static Builder builder(String space, String documentId) {
        return new Builder(space, documentId);
    }

    /**
     * @return 目标 Space。
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
     * @return 调用方内容摘要；为空时由服务计算。
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
     * @return 抽取质量选项。
     */
    public GraphExtractionOptions getExtractionOptions() {
        return extractionOptions;
    }

    /**
     * @return 图操作选项。
     */
    public GraphOptions getGraphOptions() {
        return graphOptions;
    }

    /**
     * @return 旧关系处理策略。
     */
    public StaleRelationPolicy getStaleRelationPolicy() {
        return staleRelationPolicy;
    }

    /**
     * @return 是否拒绝带 ERROR 的部分抽取结果。
     */
    public boolean isRejectExtractionErrors() {
        return rejectExtractionErrors;
    }

    /**
     * @return 是否强制重新抽取未变化内容。
     */
    public boolean isForceReextract() {
        return forceReextract;
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
     * 增量导入请求构造器。
     */
    public static final class Builder {
        /**
         * 目标 Space。
         */
        private final String space;
        /**
         * 逻辑文档 ID。
         */
        private final String documentId;
        /**
         * 调用方摘要。
         */
        private String contentHash;
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
         * 抽取选项。
         */
        private GraphExtractionOptions extractionOptions;
        /**
         * 图操作选项。
         */
        private GraphOptions graphOptions;
        /**
         * 旧关系策略。
         */
        private StaleRelationPolicy staleRelationPolicy = StaleRelationPolicy.KEEP;
        /**
         * 默认拒绝带 ERROR 的部分结果，避免把失败版本提交为当前状态。
         */
        private boolean rejectExtractionErrors = true;
        /**
         * 默认对相同摘要执行快速跳过。
         */
        private boolean forceReextract;

        private Builder(String space, String documentId) {
            this.space = space;
            this.documentId = documentId;
        }

        /**
         * 设置可信内容摘要。
         */
        public Builder contentHash(String value) {
            contentHash = value;
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
         * 设置抽取质量选项。
         */
        public Builder extractionOptions(GraphExtractionOptions value) {
            extractionOptions = value;
            return this;
        }

        /**
         * 设置显式指向同一 Space 的图操作选项。
         */
        public Builder graphOptions(GraphOptions value) {
            graphOptions = value;
            return this;
        }

        /**
         * 设置旧关系处理策略。
         */
        public Builder staleRelationPolicy(StaleRelationPolicy value) {
            staleRelationPolicy = value;
            return this;
        }

        /**
         * 设置是否拒绝带 ERROR 的部分结果。
         */
        public Builder rejectExtractionErrors(boolean value) {
            rejectExtractionErrors = value;
            return this;
        }

        /**
         * 设置是否忽略内容判重并强制重新抽取。
         */
        public Builder forceReextract(boolean value) {
            forceReextract = value;
            return this;
        }

        /**
         * @return 校验并冻结后的增量请求。
         */
        public IncrementalGraphIngestionRequest build() {
            return new IncrementalGraphIngestionRequest(this);
        }
    }
}

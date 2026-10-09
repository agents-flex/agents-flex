package com.agentsflex.graph.extractor;

/**
 * 一次知识抽取流水线的不可变质量、上下文和容错选项。
 *
 * <p>选项同时影响提示上下文、JSON 候选数量和 Schema 校验结果。实例由 Builder 创建后不再
 * 改变，可以被多个并发抽取请求安全复用。</p>
 */
public final class GraphExtractionOptions {
    /**
     * 默认抽取选项，适用于要求证据、只接收明确事实的常规场景。
     */
    public static final GraphExtractionOptions DEFAULT = builder().build();
    /**
     * 候选最低置信度，闭区间范围为 0 到 1。
     */
    private final double minConfidence;
    /**
     * 是否要求每个候选携带当前 Chunk 的连续原文证据。
     */
    private final boolean requireEvidence;
    /**
     * 是否允许 INFERRED 关系进入实体归一和 GraphMutation 阶段。
     */
    private final boolean includeInferredRelations;
    /**
     * 是否允许 OPINION 关系进入实体归一和 GraphMutation 阶段。
     */
    private final boolean includeOpinionRelations;
    /**
     * 传给下一 Chunk 用于指代消解的前文最大字符数。
     */
    private final int maxPreviousContextCharacters;
    /**
     * JSON 解析阶段保留的单 Chunk 实体候选上限。
     */
    private final int maxEntitiesPerChunk;
    /**
     * JSON 解析阶段保留的单 Chunk 关系候选上限。
     */
    private final int maxRelationsPerChunk;
    /**
     * 单次模型原始响应允许的最大字符数。
     */
    private final int maxResponseCharacters;
    /**
     * 单个 Chunk 调用或解析失败时是否立即终止整个文档。
     */
    private final boolean failOnChunkError;

    /**
     * 从构造器复制全部字段，形成不可变配置快照。
     */
    private GraphExtractionOptions(Builder builder) {
        minConfidence = builder.minConfidence;
        requireEvidence = builder.requireEvidence;
        includeInferredRelations = builder.includeInferredRelations;
        includeOpinionRelations = builder.includeOpinionRelations;
        maxPreviousContextCharacters = builder.maxPreviousContextCharacters;
        maxEntitiesPerChunk = builder.maxEntitiesPerChunk;
        maxRelationsPerChunk = builder.maxRelationsPerChunk;
        maxResponseCharacters = builder.maxResponseCharacters;
        failOnChunkError = builder.failOnChunkError;
    }

    /**
     * @return 最低置信度。
     */
    public double getMinConfidence() {
        return minConfidence;
    }

    /**
     * @return 是否要求证据。
     */
    public boolean isRequireEvidence() {
        return requireEvidence;
    }

    /**
     * @return 是否包含推断关系。
     */
    public boolean isIncludeInferredRelations() {
        return includeInferredRelations;
    }

    /**
     * @return 是否包含观点关系。
     */
    public boolean isIncludeOpinionRelations() {
        return includeOpinionRelations;
    }

    /**
     * @return 传给下一分段的前文最大字符数；0 表示不携带前文。
     */
    public int getMaxPreviousContextCharacters() {
        return maxPreviousContextCharacters;
    }

    /**
     * @return 单分段最大实体数。
     */
    public int getMaxEntitiesPerChunk() {
        return maxEntitiesPerChunk;
    }

    /**
     * @return 单分段最大关系数。
     */
    public int getMaxRelationsPerChunk() {
        return maxRelationsPerChunk;
    }

    /**
     * @return 单次模型响应最大字符数。
     */
    public int getMaxResponseCharacters() {
        return maxResponseCharacters;
    }

    /**
     * @return 分段失败时是否立即终止。
     */
    public boolean isFailOnChunkError() {
        return failOnChunkError;
    }

    /**
     * 返回影响抽取结果的稳定配置指纹输入。
     *
     * <p>该值供入图服务组合模型、Schema 和提示词版本生成最终 extraction fingerprint；
     * 新增会影响抽取结果的字段时必须同步加入这里。</p>
     */
    public String fingerprint() {
        return "minConfidence=" + minConfidence
            + ";requireEvidence=" + requireEvidence
            + ";includeInferredRelations=" + includeInferredRelations
            + ";includeOpinionRelations=" + includeOpinionRelations
            + ";maxPreviousContextCharacters=" + maxPreviousContextCharacters
            + ";maxEntitiesPerChunk=" + maxEntitiesPerChunk
            + ";maxRelationsPerChunk=" + maxRelationsPerChunk
            + ";maxResponseCharacters=" + maxResponseCharacters
            + ";failOnChunkError=" + failOnChunkError;
    }

    /**
     * @return 新构造器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 抽取选项构造器，初始值与 {@link GraphExtractionOptions#DEFAULT} 一致。
     */
    public static final class Builder {
        /**
         * 构造阶段暂存的最低置信度。
         */
        private double minConfidence = 0.6D;
        /**
         * 构造阶段暂存的证据要求。
         */
        private boolean requireEvidence = true;
        /**
         * 构造阶段暂存的推断关系开关。
         */
        private boolean includeInferredRelations;
        /**
         * 构造阶段暂存的观点关系开关。
         */
        private boolean includeOpinionRelations;
        /**
         * 构造阶段暂存的前文字符上限。
         */
        private int maxPreviousContextCharacters = 1000;
        /**
         * 构造阶段暂存的实体数量上限。
         */
        private int maxEntitiesPerChunk = 200;
        /**
         * 构造阶段暂存的关系数量上限。
         */
        private int maxRelationsPerChunk = 200;
        /**
         * 构造阶段暂存的模型响应字符上限。
         */
        private int maxResponseCharacters = 1_000_000;
        /**
         * 构造阶段暂存的分段失败策略。
         */
        private boolean failOnChunkError = true;

        /**
         * 设置候选最低置信度。
         *
         * @param value 0 到 1 的闭区间数值
         * @return 当前构造器
         */
        public Builder minConfidence(double value) {
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0D || value > 1D) {
                throw new IllegalArgumentException("minConfidence must be a finite number between 0 and 1");
            }
            minConfidence = value;
            return this;
        }

        /**
         * 设置是否要求候选引用当前 Chunk 的原文证据。
         */
        public Builder requireEvidence(boolean value) {
            requireEvidence = value;
            return this;
        }

        /**
         * 设置是否接受模型标记为 INFERRED 的推断关系。
         */
        public Builder includeInferredRelations(boolean value) {
            includeInferredRelations = value;
            return this;
        }

        /**
         * 设置是否接受模型标记为 OPINION 的主观观点关系。
         */
        public Builder includeOpinionRelations(boolean value) {
            includeOpinionRelations = value;
            return this;
        }

        /**
         * 设置传给下一 Chunk 的前文字符上限；设为 0 可关闭跨 Chunk 上下文。
         */
        public Builder maxPreviousContextCharacters(int value) {
            if (value < 0) throw new IllegalArgumentException("maxPreviousContextCharacters must not be negative");
            maxPreviousContextCharacters = value;
            return this;
        }

        /**
         * 设置单个 Chunk 可保留的最大实体候选数，超出部分会截断并记录警告。
         */
        public Builder maxEntitiesPerChunk(int value) {
            if (value <= 0) throw new IllegalArgumentException("maxEntitiesPerChunk must be positive");
            maxEntitiesPerChunk = value;
            return this;
        }

        /**
         * 设置单个 Chunk 可保留的最大关系候选数，超出部分会截断并记录警告。
         */
        public Builder maxRelationsPerChunk(int value) {
            if (value <= 0) throw new IllegalArgumentException("maxRelationsPerChunk must be positive");
            maxRelationsPerChunk = value;
            return this;
        }

        /**
         * 设置单次模型响应最大字符数，超限响应会在 JSON 解析前失败，避免异常输出消耗过多内存和 CPU。
         */
        public Builder maxResponseCharacters(int value) {
            if (value <= 0) throw new IllegalArgumentException("maxResponseCharacters must be positive");
            maxResponseCharacters = value;
            return this;
        }

        /**
         * 设置分段抽取失败时是否立即终止；关闭后会记录 CHUNK_EXTRACTION_FAILED 并继续后续分段。
         */
        public Builder failOnChunkError(boolean value) {
            failOnChunkError = value;
            return this;
        }

        /**
         * @return 完成所有参数校验后的不可变选项
         */
        public GraphExtractionOptions build() {
            return new GraphExtractionOptions(this);
        }
    }
}

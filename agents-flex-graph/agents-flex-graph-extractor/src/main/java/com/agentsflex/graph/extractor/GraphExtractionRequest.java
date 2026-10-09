package com.agentsflex.graph.extractor;

import com.agentsflex.graph.schema.GraphSchema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个文本分段的不可变知识抽取请求。
 *
 * <p>请求同时携带当前正文、只读消歧上下文、Schema、来源信息和质量选项。扩展实现应只从
 * {@link #getText()} 抽取事实，不能把 {@link #getPreviousContext()} 中的内容当成当前 Chunk 的证据。</p>
 */
public final class GraphExtractionRequest {
    /**
     * 当前分段正文，也是 evidence 允许引用的唯一文本范围。
     */
    private final String text;
    /**
     * 限制候选节点、关系和属性的图 Schema。
     */
    private final GraphSchema schema;
    /**
     * 来源文档的稳定 ID；预分段入口无法确定时为空字符串。
     */
    private final String documentId;
    /**
     * 当前分段的唯一 ID，同时用于 mentionId 作用域和证据定位。
     */
    private final String chunkId;
    /**
     * 只用于指代消解的前文，不允许作为候选证据。
     */
    private final String previousContext;
    /**
     * 从来源 Document 复制的只读元数据快照。
     */
    private final Map<String, Object> metadata;
    /**
     * 本次抽取使用的质量阈值、数量上限和断言策略。
     */
    private final GraphExtractionOptions options;

    /**
     * 校验构造器必填字段并建立不可变请求快照。
     */
    private GraphExtractionRequest(Builder builder) {
        if (builder.text == null || builder.text.trim().isEmpty())
            throw new IllegalArgumentException("text must not be blank");
        if (builder.schema == null) throw new IllegalArgumentException("schema must not be null");
        if (builder.chunkId == null || builder.chunkId.trim().isEmpty())
            throw new IllegalArgumentException("chunkId must not be blank");
        text = builder.text;
        schema = builder.schema;
        documentId = builder.documentId == null ? "" : builder.documentId.trim();
        chunkId = builder.chunkId.trim();
        previousContext = builder.previousContext == null ? "" : builder.previousContext;
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        options = builder.options == null ? GraphExtractionOptions.DEFAULT : builder.options;
    }

    /**
     * @return 当前分段正文。
     */
    public String getText() {
        return text;
    }

    /**
     * @return 限制模型输出和后续校验的图 Schema。
     */
    public GraphSchema getSchema() {
        return schema;
    }

    /**
     * @return 来源文档 ID；未知时为空字符串。
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * @return 流水线内唯一的当前分段 ID。
     */
    public String getChunkId() {
        return chunkId;
    }

    /**
     * @return 只用于消歧的前文上下文。
     */
    public String getPreviousContext() {
        return previousContext;
    }

    /**
     * @return 不可修改的来源元数据快照。
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * @return 当前分段使用的抽取选项。
     */
    public GraphExtractionOptions getOptions() {
        return options;
    }

    /**
     * 创建单分段请求构造器。
     *
     * @param text    非空当前分段正文
     * @param schema  非空图 Schema
     * @param chunkId 流水线内唯一的非空分段 ID
     * @return 新请求构造器
     */
    public static Builder builder(String text, GraphSchema schema, String chunkId) {
        return new Builder(text, schema, chunkId);
    }

    /**
     * 单分段抽取请求构造器。
     *
     * <p>构造器可以复用调用方元数据，但 {@link #build()} 会复制映射，之后修改原映射不会影响
     * 已创建的请求。</p>
     */
    public static final class Builder {
        /**
         * 待抽取的当前文本。
         */
        private final String text;
        /**
         * 限制模型输出的图 Schema。
         */
        private final GraphSchema schema;
        /**
         * 当前分段的作用域标识。
         */
        private final String chunkId;
        /**
         * 来源文档标识。
         */
        private String documentId;
        /**
         * 仅用于指代消解的前文。
         */
        private String previousContext;
        /**
         * 待复制到证据对象的来源元数据。
         */
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        /**
         * 本次请求使用的质量和上下文选项。
         */
        private GraphExtractionOptions options;

        /**
         * 保存必填字段，最终合法性由 {@link #build()} 统一校验。
         */
        private Builder(String text, GraphSchema schema, String chunkId) {
            this.text = text;
            this.schema = schema;
            this.chunkId = chunkId;
        }

        /**
         * @param value 来源文档 ID；允许为 {@code null}
         * @return 当前构造器
         */
        public Builder documentId(String value) {
            documentId = value;
            return this;
        }

        /**
         * @param value 仅用于指代消解的前文；允许为 {@code null}
         * @return 当前构造器
         */
        public Builder previousContext(String value) {
            previousContext = value;
            return this;
        }

        /**
         * @param values 要复制到证据中的来源元数据
         * @return 当前构造器
         */
        public Builder metadata(Map<String, ?> values) {
            if (values != null) metadata.putAll(values);
            return this;
        }

        /**
         * @param value 本次请求选项；为 {@code null} 时使用默认值
         * @return 当前构造器
         */
        public Builder options(GraphExtractionOptions value) {
            options = value;
            return this;
        }

        /**
         * @return 完成参数校验后的不可变请求
         */
        public GraphExtractionRequest build() {
            return new GraphExtractionRequest(this);
        }
    }
}

package com.agentsflex.graph.extractor.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 候选实体或关系的原文证据及来源定位。
 *
 * <p>证据对象不会被写入数据库属性，它首先服务于审核、纠错和溯源。调用方可以根据自己的
 * GraphSchema 决定是否把其中部分字段映射为节点或边属性。</p>
 */
public final class GraphEvidence {
    /**
     * 来源文档的稳定标识。
     */
    private final String documentId;
    /**
     * 当前分段在文档内的稳定标识。
     */
    private final String chunkId;
    /**
     * 能直接支持候选事实的原文摘录。
     */
    private final String quote;
    /**
     * 摘录在原文中的起始偏移；无法确定时为 -1。
     */
    private final int startOffset;
    /**
     * 摘录在原文中的 exclusive 结束偏移；无法确定时为 -1。
     */
    private final int endOffset;
    /**
     * 从 Document 继承的只读来源元数据。
     */
    private final Map<String, Object> metadata;

    /**
     * 创建不可变证据。
     *
     * @param documentId  文档标识，可以为空字符串
     * @param chunkId     分段标识，不能为空
     * @param quote       原文摘录，可以为空字符串
     * @param startOffset 起始偏移，未知时传 -1
     * @param endOffset   exclusive 结束偏移，未知时传 -1
     * @param metadata    来源元数据
     */
    public GraphEvidence(String documentId, String chunkId, String quote, int startOffset, int endOffset,
                         Map<String, ?> metadata) {
        if (chunkId == null || chunkId.trim().isEmpty())
            throw new IllegalArgumentException("chunkId must not be blank");
        boolean unknownOffsets = startOffset == -1 && endOffset == -1;
        boolean knownOffsets = startOffset >= 0 && endOffset >= startOffset;
        if (!unknownOffsets && !knownOffsets) {
            throw new IllegalArgumentException("invalid evidence offsets");
        }
        this.documentId = documentId == null ? "" : documentId.trim();
        this.chunkId = chunkId.trim();
        this.quote = quote == null ? "" : quote;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (metadata != null) copy.putAll(metadata);
        this.metadata = Collections.unmodifiableMap(copy);
    }

    /**
     * @return 来源文档标识；预分段入口无法确定时为空字符串。
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * @return 当前 Chunk 标识，也是候选 mentionId 的作用域。
     */
    public String getChunkId() {
        return chunkId;
    }

    /**
     * @return 支持候选事实的当前 Chunk 连续原文摘录。
     */
    public String getQuote() {
        return quote;
    }

    /**
     * @return quote 起始字符下标，未知时为 -1。
     */
    public int getStartOffset() {
        return startOffset;
    }

    /**
     * @return exclusive 结束偏移，未知时为 -1。
     */
    public int getEndOffset() {
        return endOffset;
    }

    /**
     * @return 从 Document 复制而来的只读来源元数据。
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }
}

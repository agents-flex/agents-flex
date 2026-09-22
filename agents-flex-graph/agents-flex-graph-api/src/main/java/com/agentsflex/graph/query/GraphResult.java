package com.agentsflex.graph.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 图查询物化后的结果集。
 */
public final class GraphResult {
    /**
     * 查询记录。
     */
    private final List<GraphRecord> records;
    /**
     * 实际执行的后端查询文本，便于审计和调试。
     */
    private final String queryText;
    /**
     * 查询执行与物化元数据。
     */
    private final GraphResultMetadata metadata;

    /**
     * 创建结果集并冻结记录列表。
     */
    public GraphResult(List<GraphRecord> records, String queryText) {
        this(records, queryText, null);
    }

    /**
     * 创建包含执行元数据的结果集。
     */
    public GraphResult(List<GraphRecord> records, String queryText, GraphResultMetadata metadata) {
        this.records = Collections.unmodifiableList(new ArrayList<>(records == null
            ? Collections.<GraphRecord>emptyList() : records));
        this.queryText = queryText;
        this.metadata = metadata == null
            ? new GraphResultMetadata(this.records.size(), false, 0L) : metadata;
    }

    /**
     * @return 只读记录列表
     */
    public List<GraphRecord> getRecords() {
        return records;
    }

    /**
     * @return 实际查询文本
     */
    public String getQueryText() {
        return queryText;
    }

    /**
     * @return 查询执行与物化元数据
     */
    public GraphResultMetadata getMetadata() {
        return metadata;
    }
}

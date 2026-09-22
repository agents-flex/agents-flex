package com.agentsflex.graph.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 后端实际 Schema 的读取结果及兼容性警告。
 */
public final class GraphSchemaInspection {
    private final GraphSchema schema;
    private final boolean complete;
    private final List<String> warnings;

    /**
     * 创建 Schema 读取结果。
     */
    public GraphSchemaInspection(GraphSchema schema, boolean complete, List<String> warnings) {
        if (schema == null) throw new IllegalArgumentException("schema must not be null");
        this.schema = schema;
        this.complete = complete;
        this.warnings = warnings == null ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /**
     * @return 从后端读取的 Schema
     */
    public GraphSchema getSchema() {
        return schema;
    }

    /**
     * @return 是否完整覆盖节点、边、属性和索引元数据
     */
    public boolean isComplete() {
        return complete;
    }

    /**
     * @return 无法准确映射到可移植模型的内容说明
     */
    public List<String> getWarnings() {
        return warnings;
    }
}

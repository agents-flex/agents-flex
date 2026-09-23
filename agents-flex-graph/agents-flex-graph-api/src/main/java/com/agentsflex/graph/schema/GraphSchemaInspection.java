package com.agentsflex.graph.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 后端实际 Schema 的读取结果及兼容性警告。
 */
public final class GraphSchemaInspection {
    /**
     * 从后端反查并转换后的可移植 Schema。
     */
    private final GraphSchema schema;
    /**
     * 是否完整覆盖后端可见的节点、边、属性和索引信息。
     */
    private final boolean complete;
    /**
     * 反查过程中的兼容性警告。
     */
    private final List<String> warnings;
    /**
     * 后端无法映射到可移植 Schema 的元数据说明。
     */
    private final List<String> unsupportedMetadata;
    /**
     * 反查时记录的后端版本；适配器无法提供时为空。
     */
    private final String backendVersion;
    /**
     * 反查完成时间。
     */
    private final long inspectedAtMillis;

    /**
     * 创建没有后端扩展信息的 Schema 读取结果。
     */
    public GraphSchemaInspection(GraphSchema schema, boolean complete, List<String> warnings) {
        this(schema, complete, warnings, Collections.<String>emptyList(), "", System.currentTimeMillis());
    }

    /**
     * 创建带后端版本和不可映射元数据说明的反查结果。
     *
     * @param schema              转换后的 Schema，不允许为 {@code null}
     * @param complete            是否完整反查
     * @param warnings            兼容性警告
     * @param unsupportedMetadata 无法映射的后端元数据名称
     * @param backendVersion      后端版本，未知时为空
     * @param inspectedAtMillis   反查完成时间
     */
    public GraphSchemaInspection(GraphSchema schema, boolean complete, List<String> warnings,
                                 List<String> unsupportedMetadata, String backendVersion, long inspectedAtMillis) {
        if (schema == null) throw new IllegalArgumentException("schema must not be null");
        this.schema = schema;
        this.complete = complete;
        this.warnings = warnings == null ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(warnings));
        this.unsupportedMetadata = unsupportedMetadata == null ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(unsupportedMetadata));
        this.backendVersion = backendVersion == null ? "" : backendVersion;
        this.inspectedAtMillis = inspectedAtMillis;
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

    /**
     * @return 后端存在但无法映射到可移植模型的元数据名称。
     */
    public List<String> getUnsupportedMetadata() {
        return unsupportedMetadata;
    }

    /**
     * @return 反查时读取到的后端版本。
     */
    public String getBackendVersion() {
        return backendVersion;
    }

    /**
     * @return 反查完成时间。
     */
    public long getInspectedAtMillis() {
        return inspectedAtMillis;
    }
}

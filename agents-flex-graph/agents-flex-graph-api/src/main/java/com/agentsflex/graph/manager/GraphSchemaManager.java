package com.agentsflex.graph.manager;

import com.agentsflex.graph.UnsupportedGraphFeatureException;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaApplyResult;
import com.agentsflex.graph.schema.GraphSchemaInspection;
import com.agentsflex.graph.schema.GraphSchemaValidation;

/**
 * 图 Schema 生命周期管理契约。
 *
 * <p>该接口只负责 Schema 校验、应用和反查；版本存储、审批和迁移调度仍由使用 SDK 的应用负责。</p>
 */
public interface GraphSchemaManager {
    /**
     * 按指定策略应用节点、边类型和索引定义。
     */
    void applySchema(String space, GraphSchema schema, GraphManager.SchemaMode mode);

    /**
     * 携带请求上下文应用 Schema；默认实现保持旧适配器兼容。
     */
    default void applySchema(String space, GraphSchema schema, GraphManager.SchemaMode mode,
                             GraphOptions options) {
        applySchema(space, schema, mode);
    }

    /**
     * 应用 Schema 并返回结构化执行结果。
     */
    default GraphSchemaApplyResult applySchemaResult(String space, GraphSchema schema,
                                                     GraphManager.SchemaMode mode) {
        long started = System.currentTimeMillis();
        try {
            applySchema(space, schema, mode == null ? GraphManager.SchemaMode.ADDITIVE : mode);
            return GraphSchemaApplyResult.success(java.util.Collections.<String>emptyList(),
                java.util.Collections.<String>emptyList(), System.currentTimeMillis() - started);
        } catch (RuntimeException error) {
            return GraphSchemaApplyResult.failure(error, System.currentTimeMillis() - started);
        }
    }

    /**
     * 比较 Schema 兼容性但不执行变更。
     */
    GraphSchemaValidation validateSchema(String space, GraphSchema schema);

    /**
     * 携带请求上下文校验 Schema；默认实现保持旧适配器兼容。
     */
    default GraphSchemaValidation validateSchema(String space, GraphSchema schema, GraphOptions options) {
        return validateSchema(space, schema);
    }

    /**
     * 从后端读取当前 Schema。
     */
    default GraphSchemaInspection inspectSchema(String space) {
        throw new UnsupportedGraphFeatureException("Graph backend does not support schema introspection");
    }

    /**
     * 携带请求上下文反查 Schema；默认实现保持旧适配器兼容。
     */
    default GraphSchemaInspection inspectSchema(String space, GraphOptions options) {
        return inspectSchema(space);
    }
}

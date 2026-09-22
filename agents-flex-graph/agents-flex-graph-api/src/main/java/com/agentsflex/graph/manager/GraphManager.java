package com.agentsflex.graph.manager;

import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaInspection;
import com.agentsflex.graph.schema.GraphSchemaValidation;
import com.agentsflex.graph.manager.GraphSpaceDefinition;
import com.agentsflex.graph.UnsupportedGraphFeatureException;

import java.util.List;

/**
 * 管理逻辑图空间及其可移植 Schema。
 */
public interface GraphManager {
    /**
     * 创建空间时对已存在空间的处理策略。
     */
    enum CreateMode {
        /**
         * 已存在时失败。
         */
        FAIL_IF_EXISTS,
        /**
         * 已存在时复用，不存在时创建。
         */
        IF_ABSENT,
        /**
         * 只校验，不执行 DDL。
         */
        VALIDATE_ONLY
    }

    /**
     * 应用 Schema 时允许的变更范围。
     */
    enum SchemaMode {
        /**
         * 只校验，不执行 DDL。
         */
        VALIDATE_ONLY,
        /**
         * 仅创建缺失定义。
         */
        CREATE_IF_ABSENT,
        /**
         * 应用兼容的增量定义。
         */
        ADDITIVE
    }

    /**
     * 创建或校验逻辑图空间。
     */
    void createSpace(GraphSpaceDefinition definition, CreateMode mode);

    /**
     * 使用 {@link CreateMode#IF_ABSENT} 创建空间。
     */
    default void createSpace(GraphSpaceDefinition definition) {
        createSpace(definition, CreateMode.IF_ABSENT);
    }

    /**
     * @return 指定空间是否存在
     */
    boolean spaceExists(String name);

    /**
     * @return 当前后端可见的空间名称
     */
    List<String> listSpaces();

    /**
     * 删除逻辑图空间及其数据。
     */
    void dropSpace(String name);

    /**
     * 按指定策略应用节点、边类型和索引定义。
     */
    void applySchema(String space, GraphSchema schema, SchemaMode mode);

    /**
     * 使用增量策略应用 Schema。
     */
    default void applySchema(String space, GraphSchema schema) {
        applySchema(space, schema, SchemaMode.ADDITIVE);
    }

    /**
     * 比较 Schema 兼容性但不执行变更。
     */
    GraphSchemaValidation validateSchema(String space, GraphSchema schema);

    /**
     * 从后端读取当前 Schema。
     *
     * @throws UnsupportedGraphFeatureException 适配器不支持 Schema introspection 时抛出
     */
    default GraphSchemaInspection inspectSchema(String space) {
        throw new UnsupportedGraphFeatureException("Graph backend does not support schema introspection");
    }
}

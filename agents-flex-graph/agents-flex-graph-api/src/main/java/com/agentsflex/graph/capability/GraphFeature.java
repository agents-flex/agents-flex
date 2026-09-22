package com.agentsflex.graph.capability;

import com.agentsflex.graph.GraphStore;

/**
 * 图存储实现对外声明的能力集合。
 *
 * <p>调用方可以先通过 {@link GraphStore#capabilities()} 检查能力，再决定是否使用
 * 某个可选功能。适配器不应在不支持某个能力时静默降级。</p>
 */
public enum GraphFeature {
    /**
     * 创建逻辑图空间。
     */
    CREATE_SPACE,
    /**
     * 删除逻辑图空间。
     */
    DROP_SPACE,
    /**
     * 创建或校验节点、边的 Schema。
     */
    SCHEMA,
    /**
     * 从后端读取当前节点、边和索引定义。
     */
    SCHEMA_INTROSPECTION,
    /**
     * 创建普通索引。
     */
    INDEX,
    /**
     * 创建唯一约束或唯一索引。
     */
    UNIQUE_CONSTRAINT,
    /**
     * 一个节点可以同时拥有多个标签。
     */
    MULTI_LABEL,
    /**
     * 支持显式事务边界。
     */
    TRANSACTIONS,
    /**
     * 支持有限范围的变长路径。
     */
    VARIABLE_LENGTH_PATH,
    /**
     * 支持最短路径查询。
     */
    SHORTEST_PATH,
    /**
     * 支持批量导入能力。
     */
    BULK_IMPORT,
    /**
     * 支持执行后端原生查询语言。
     */
    NATIVE_QUERY
}

package com.agentsflex.graph.query;

import com.agentsflex.graph.GraphOptions;

/**
 * 执行可移植查询和显式原生查询。
 */
public interface GraphQueryExecutor {
    /**
     * 执行统一查询语义。
     */
    GraphResult execute(GraphQuery query, GraphOptions options);

    /**
     * 执行后端原生查询。
     */
    GraphResult execute(NativeGraphQuery query, GraphOptions options);

    /**
     * 使用默认选项执行统一查询。
     */
    default GraphResult execute(GraphQuery query) {
        return execute(query, GraphOptions.DEFAULT);
    }

    /**
     * 使用默认选项执行原生查询。
     */
    default GraphResult execute(NativeGraphQuery query) {
        return execute(query, GraphOptions.DEFAULT);
    }
}

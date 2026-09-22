package com.agentsflex.graph;

import com.agentsflex.graph.capability.GraphCapabilities;
import com.agentsflex.graph.connection.GraphHealth;
import com.agentsflex.graph.importing.GraphImportService;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.transaction.GraphTransactionManager;
import com.agentsflex.graph.mutation.GraphWriter;

/**
 * 图数据库统一门面，组合能力、空间管理、读写和事务入口。
 */
public interface GraphStore extends AutoCloseable {
    /**
     * @return 后端能力声明
     */
    GraphCapabilities capabilities();

    /**
     * @return 图空间和 Schema 管理器
     */
    GraphManager manager();

    /**
     * @return 节点、边写入器
     */
    GraphWriter writer();

    /**
     * @return 统一查询执行器
     */
    GraphQueryExecutor query();

    /**
     * @return 显式事务管理器
     */
    GraphTransactionManager transactions();

    /**
     * @return 连接健康检查入口
     */
    default GraphHealth health() {
        return GraphHealth.unknown(getClass().getName(), "Health check is not implemented");
    }

    /**
     * @return 异步导入任务服务
     */
    default GraphImportService imports() {
        throw new UnsupportedGraphFeatureException("Graph backend does not expose async imports");
    }

    /**
     * 释放连接池和驱动资源。
     */
    @Override
    void close();
}

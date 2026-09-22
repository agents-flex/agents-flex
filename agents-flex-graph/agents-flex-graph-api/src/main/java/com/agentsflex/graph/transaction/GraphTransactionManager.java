package com.agentsflex.graph.transaction;

import com.agentsflex.graph.transaction.GraphTransaction;
import com.agentsflex.graph.GraphOptions;

/**
 * 启动显式图事务。
 */
public interface GraphTransactionManager {
    /**
     * @param options 事务使用的空间和超时选项 @return 新事务
     */
    GraphTransaction begin(GraphOptions options);
}

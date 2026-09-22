package com.agentsflex.graph.transaction;

import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.mutation.GraphWriter;

/**
 * 支持显式事务的图存储边界。
 */
public interface GraphTransaction extends AutoCloseable {
    /**
     * @return 绑定到当前事务的写入器
     */
    GraphWriter writer();

    /**
     * @return 绑定到当前事务的查询执行器
     */
    GraphQueryExecutor query();

    /**
     * 提交事务。
     */
    void commit();

    /**
     * 回滚事务。
     */
    void rollback();

    /**
     * 关闭事务；实现通常会回滚尚未完成的事务。
     */
    @Override
    void close();
}

package com.agentsflex.graph.neo4j.transaction;

import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.transaction.GraphTransaction;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import com.agentsflex.graph.neo4j.mutation.Neo4jGraphWriter;
import com.agentsflex.graph.neo4j.query.Neo4jGraphQueryExecutor;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

/**
 * Neo4j 驱动事务的 GraphTransaction 适配器。
 */
public final class Neo4jGraphTransaction implements GraphTransaction {
    /**
     * 持有事务的会话。
     */
    private final Session session;
    /**
     * 官方驱动事务。
     */
    private final Transaction transaction;
    /**
     * 绑定当前事务的写入器。
     */
    private final GraphWriter writer;
    /**
     * 绑定当前事务的查询执行器。
     */
    private final GraphQueryExecutor query;
    /**
     * 是否已提交、回滚或关闭。
     */
    private boolean closed;

    /**
     * 创建事务适配器。
     */
    public Neo4jGraphTransaction(Session session, Transaction transaction, Neo4jGraphStoreConfig config) {
        this.session = session;
        this.transaction = transaction;
        this.writer = new Neo4jGraphWriter(transaction, config);
        this.query = new Neo4jGraphQueryExecutor(transaction, config);
    }

    /**
     * @return 当前事务写入器
     */
    @Override
    public GraphWriter writer() {
        ensureOpen();
        return writer;
    }

    /**
     * @return 当前事务查询执行器
     */
    @Override
    public GraphQueryExecutor query() {
        ensureOpen();
        return query;
    }

    /**
     * 提交并关闭事务资源。
     */
    @Override
    public void commit() {
        ensureOpen();
        transaction.commit();
        close();
    }

    /**
     * 回滚并关闭事务资源。
     */
    @Override
    public void rollback() {
        if (!closed) {
            transaction.rollback();
            close();
        }
    }

    /**
     * 幂等关闭；未提交事务会由驱动关闭。
     */
    @Override
    public void close() {
        if (!closed) {
            transaction.close();
            session.close();
            closed = true;
        }
    }

    /**
     * 拒绝在事务结束后继续使用其读写器。
     */
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Graph transaction is closed");
    }
}

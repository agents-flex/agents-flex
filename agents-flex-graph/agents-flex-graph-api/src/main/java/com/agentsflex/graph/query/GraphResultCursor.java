package com.agentsflex.graph.query;

import java.util.Iterator;

/**
 * 查询结果游标契约。
 *
 * <p>默认适配器可以先用物化结果实现该接口；支持原生游标的适配器可以提供真正的流式读取，
 * 从而避免把大结果集一次性加载到内存。</p>
 */
public interface GraphResultCursor extends Iterator<GraphRecord>, AutoCloseable {
    /**
     * @return 当前游标已知的执行元数据。
     */
    GraphResultMetadata getMetadata();

    /**
     * 释放后端游标和连接资源。
     */
    @Override
    void close();
}

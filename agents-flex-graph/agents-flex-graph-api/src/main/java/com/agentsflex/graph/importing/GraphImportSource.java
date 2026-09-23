package com.agentsflex.graph.importing;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;

/**
 * 可由开发者适配任意外部数据源的节点/边流。
 *
 * <p>SDK 不负责解析 CSV、数据库或对象存储；调用方只需把数据源转换为两个可迭代批次，
 * 导入服务即可复用统一的顺序、取消、监听和 checkpoint 语义。</p>
 */
public interface GraphImportSource extends AutoCloseable {
    /**
     * @return 节点数据流，必须先于边数据流消费。
     */
    Iterable<GraphNode> nodes();

    /**
     * @return 边数据流。
     */
    Iterable<GraphEdge> edges();

    /**
     * 数据源资源释放钩子。
     */
    @Override
    default void close() {
    }
}

package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.graph.data.GraphEdgeKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 长期保存文档版本及其关系来源集合的扩展点。
 *
 * <p>生产实现应把 {@link #compareAndSet} 和 {@link #remove} 做成原子乐观锁操作。SDK 会先写图，
 * 写成功后再提交状态；若状态提交失败，调用方可以使用相同 documentId 和内容摘要安全重试图 upsert。</p>
 */
public interface GraphDocumentStateStore {
    /**
     * 查询指定 Space 中逻辑文档的当前已提交状态，不存在时返回 null。
     */
    GraphDocumentState get(String space, String documentId);

    /**
     * 返回指定 Space 的状态快照；实现可以覆写引用查询以避免全表扫描。
     */
    List<GraphDocumentState> list(String space);

    /**
     * 比较并设置文档状态。
     *
     * @param expectedRevision 期望旧 revision；文档不存在时传 0
     * @return 提交成功时为 true，状态已经被其他导入修改时为 false
     */
    boolean compareAndSet(String space, String documentId, long expectedRevision, GraphDocumentState newState);

    /**
     * 按 revision 原子删除文档状态，常用于撤回整个逻辑文档。
     */
    boolean remove(String space, String documentId, long expectedRevision);

    /**
     * 判断指定关系是否仍被同一 Space 中的其他活动文档引用。
     *
     * <p>默认实现扫描 {@link #list(String)}；大规模持久化实现应建立反向索引并覆写本方法。</p>
     */
    default boolean isReferencedByOtherDocument(String space, String excludedDocumentId, GraphEdgeKey edgeKey) {
        for (GraphDocumentState state : list(space)) {
            if (!state.getDocumentId().equals(excludedDocumentId) && state.getEdgeKeys().contains(edgeKey)) return true;
        }
        return false;
    }

    /**
     * 查询一条关系在当前活动文档版本中的全部来源证据。
     *
     * <p>默认实现扫描 Space 状态；持久化实现可以通过 edgeKey 反向索引加速。</p>
     */
    default List<GraphFactProvenance> findProvenance(String space, GraphEdgeKey edgeKey) {
        List<GraphFactProvenance> result = new ArrayList<>();
        for (GraphDocumentState state : list(space)) {
            for (GraphFactProvenance provenance : state.getFactProvenances()) {
                if (provenance.getEdgeKey().equals(edgeKey)) result.add(provenance);
            }
        }
        return Collections.unmodifiableList(result);
    }
}

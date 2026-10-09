package com.agentsflex.graph.extractor.ingestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线程安全的进程内文档状态存储。
 *
 * <p>适合测试和单实例临时任务，进程退出后状态会丢失。长期知识库必须替换为数据库、缓存或
 * 任务系统实现，否则重启后无法执行内容判重和安全的旧关系回收。</p>
 */
public final class InMemoryGraphDocumentStateStore implements GraphDocumentStateStore {
    /**
     * “Space + 文档 ID”到最新不可变状态的映射。
     */
    private final Map<String, GraphDocumentState> states = new LinkedHashMap<>();
    /**
     * 文档不可变版本快照，用于测试、审计和关系回收。
     */
    private final Map<String, List<GraphDocumentState>> histories = new LinkedHashMap<>();

    /**
     * 查询当前状态。
     */
    @Override
    public synchronized GraphDocumentState findCurrent(String space, String documentId) {
        return states.get(key(space, documentId));
    }

    /**
     * 按操作号查找当前已提交版本。
     */
    @Override
    public synchronized GraphDocumentState findByOperationId(String space, String documentId, String operationId) {
        return GraphDocumentStateStore.super.findByOperationId(space, documentId, operationId);
    }

    /**
     * 按文档 ID 返回稳定顺序的只读状态列表。
     */
    @Override
    public synchronized List<GraphDocumentState> list(String space) {
        List<GraphDocumentState> result = new ArrayList<>();
        for (GraphDocumentState state : states.values()) if (state.getSpace().equals(space)) result.add(state);
        Collections.sort(result, new Comparator<GraphDocumentState>() {
            @Override
            public int compare(GraphDocumentState left, GraphDocumentState right) {
                return left.getDocumentId().compareTo(right.getDocumentId());
            }
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * 以 revision 作为乐观锁原子保存状态。
     */
    @Override
    public synchronized boolean compareAndSet(String space, String documentId, long expectedRevision,
                                              GraphDocumentState newState) {
        if (newState == null) throw new IllegalArgumentException("newState must not be null");
        if (!newState.getSpace().equals(space) || !newState.getDocumentId().equals(documentId)) {
            throw new IllegalArgumentException("newState identity does not match the storage key");
        }
        GraphDocumentState current = states.get(key(space, documentId));
        long actualRevision = current == null ? 0L : current.getRevision();
        if (actualRevision != expectedRevision || newState.getRevision() != expectedRevision + 1L) return false;
        states.put(key(space, documentId), newState);
        return true;
    }

    /**
     * 保存不可变历史快照；重复 revision 幂等忽略。
     */
    @Override
    public synchronized void recordVersion(GraphDocumentState state) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        String key = key(state.getSpace(), state.getDocumentId());
        List<GraphDocumentState> versions = histories.get(key);
        if (versions == null) {
            versions = new ArrayList<>();
            histories.put(key, versions);
        }
        for (GraphDocumentState existing : versions) {
            if (existing.getRevision() == state.getRevision()) return;
        }
        versions.add(state);
    }

    /**
     * 返回逻辑文档的不可变版本历史。
     */
    @Override
    public synchronized List<GraphDocumentState> listVersions(String space, String documentId) {
        List<GraphDocumentState> versions = histories.get(key(space, documentId));
        if (versions == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(versions));
    }

    /**
     * 以 revision 作为乐观锁原子删除状态。
     */
    @Override
    public synchronized boolean remove(String space, String documentId, long expectedRevision) {
        String key = key(space, documentId);
        GraphDocumentState current = states.get(key);
        if (current == null || current.getRevision() != expectedRevision) return false;
        states.remove(key);
        return true;
    }

    /**
     * 使用不可见分隔符避免普通名称拼接碰撞。
     */
    private static String key(String space, String documentId) {
        if (space == null || space.trim().isEmpty() || documentId == null || documentId.trim().isEmpty()) {
            throw new IllegalArgumentException("space and documentId must not be blank");
        }
        return space.trim() + "\u0000" + documentId.trim();
    }
}

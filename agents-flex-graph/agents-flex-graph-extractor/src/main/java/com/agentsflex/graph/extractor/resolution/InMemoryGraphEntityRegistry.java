package com.agentsflex.graph.extractor.resolution;

import com.agentsflex.graph.extractor.GraphExtractionException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 线程安全的进程内实体注册表。
 *
 * <p>该实现适合单实例、测试和示例；进程退出后记录会丢失。跨月或跨年的生产知识库应实现
 * {@link GraphEntityRegistry}，把相同记录持久化到可靠存储。</p>
 */
public final class InMemoryGraphEntityRegistry implements GraphEntityRegistry {
    /**
     * 节点 ID 到最新注册记录的映射。
     */
    private final Map<String, GraphRegisteredEntity> entitiesById = new LinkedHashMap<>();
    /**
     * “类型 + 规范化名称”到节点 ID 的唯一索引。
     */
    private final Map<String, String> nodeIdByName = new LinkedHashMap<>();
    /**
     * 按 Space 隔离的实体记录。
     */
    private final Map<String, Map<String, GraphRegisteredEntity>> scopedEntities = new LinkedHashMap<>();
    /**
     * 按 Space 隔离的名称索引。
     */
    private final Map<String, Map<String, String>> scopedNames = new LinkedHashMap<>();

    /**
     * 按 Space 查询实体，避免不同知识库共享名称索引。
     */
    @Override
    public synchronized List<GraphRegisteredEntity> find(String space, String type, Collection<String> names) {
        String scope = scope(space);
        Map<String, String> index = scopedNames.get(scope);
        Map<String, GraphRegisteredEntity> entities = scopedEntities.get(scope);
        if (index == null || entities == null || names == null || names.isEmpty()) return Collections.emptyList();
        Set<String> ids = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            String id = index.get(key(type, name));
            if (id != null) ids.add(id);
        }
        List<GraphRegisteredEntity> result = new ArrayList<>();
        for (String id : ids) {
            GraphRegisteredEntity entity = entities.get(id);
            if (entity != null) result.add(entity);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 按 Space 幂等保存实体并检查名称唯一性。
     */
    @Override
    public synchronized void saveAll(String space, Collection<GraphRegisteredEntity> values) {
        String scope = scope(space);
        Map<String, GraphRegisteredEntity> entities = scopedEntities.get(scope);
        Map<String, String> names = scopedNames.get(scope);
        if (entities == null) {
            entities = new LinkedHashMap<>();
            scopedEntities.put(scope, entities);
        }
        if (names == null) {
            names = new LinkedHashMap<>();
            scopedNames.put(scope, names);
        }
        Map<String, GraphRegisteredEntity> stagedEntities = new LinkedHashMap<>(entities);
        Map<String, String> stagedNames = new LinkedHashMap<>(names);
        if (values != null) for (GraphRegisteredEntity incoming : values) {
            if (incoming == null) throw new IllegalArgumentException("entities must not contain null elements");
            GraphRegisteredEntity existing = stagedEntities.get(incoming.getNodeId());
            GraphRegisteredEntity merged = merge(existing, incoming);
            assertNamesAvailable(merged, stagedNames);
            stagedEntities.put(merged.getNodeId(), merged);
            for (String name : names(merged)) stagedNames.put(key(merged.getType(), name), merged.getNodeId());
        }
        entities.clear();
        entities.putAll(stagedEntities);
        names.clear();
        names.putAll(stagedNames);
    }

    /**
     * 按类型和名称集合查找去重后的注册实体。
     */
    @Override
    public synchronized List<GraphRegisteredEntity> find(String type, Collection<String> names) {
        if (type == null || type.trim().isEmpty()) throw new IllegalArgumentException("type must not be blank");
        if (names == null || names.isEmpty()) return Collections.emptyList();
        Set<String> ids = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            String id = nodeIdByName.get(key(type, name));
            if (id != null) ids.add(id);
        }
        List<GraphRegisteredEntity> result = new ArrayList<>();
        for (String id : ids) {
            GraphRegisteredEntity entity = entitiesById.get(id);
            if (entity != null) result.add(entity);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 幂等合并实体记录，并拒绝同一个规范化名称指向两个节点 ID。
     */
    @Override
    public synchronized void saveAll(Collection<GraphRegisteredEntity> values) {
        if (values == null) return;
        // 先在副本上完成整批合并和唯一性检查，任何冲突都不会留下半批注册结果。
        Map<String, GraphRegisteredEntity> stagedEntities = new LinkedHashMap<>(entitiesById);
        Map<String, String> stagedNames = new LinkedHashMap<>(nodeIdByName);
        for (GraphRegisteredEntity incoming : values) {
            if (incoming == null) throw new IllegalArgumentException("entities must not contain null elements");
            GraphRegisteredEntity existing = stagedEntities.get(incoming.getNodeId());
            GraphRegisteredEntity merged = merge(existing, incoming);
            assertNamesAvailable(merged, stagedNames);
            stagedEntities.put(merged.getNodeId(), merged);
            for (String name : names(merged)) stagedNames.put(key(merged.getType(), name), merged.getNodeId());
        }
        entitiesById.clear();
        entitiesById.putAll(stagedEntities);
        nodeIdByName.clear();
        nodeIdByName.putAll(stagedNames);
    }

    /**
     * @return 当前注册实体数量，主要用于监控和测试。
     */
    public synchronized int size() {
        int total = entitiesById.size();
        for (Map<String, GraphRegisteredEntity> values : scopedEntities.values()) total += values.size();
        return total;
    }

    /**
     * 在真正写入索引前检查全部名称，防止部分更新。
     */
    private static void assertNamesAvailable(GraphRegisteredEntity entity, Map<String, String> namesById) {
        for (String name : names(entity)) {
            String existingId = namesById.get(key(entity.getType(), name));
            if (existingId != null && !existingId.equals(entity.getNodeId())) {
                throw new GraphExtractionException("Entity registry name is already assigned to another node: " + name);
            }
        }
    }

    /**
     * 合并同一节点多次导入发现的别名和属性。
     */
    private static GraphRegisteredEntity merge(GraphRegisteredEntity existing, GraphRegisteredEntity incoming) {
        if (existing == null) return incoming;
        if (!existing.getType().equals(incoming.getType())) {
            throw new GraphExtractionException("Registered node type cannot change: " + incoming.getNodeId());
        }
        Set<String> aliases = new LinkedHashSet<>(existing.getAliases());
        aliases.addAll(incoming.getAliases());
        Map<String, Object> properties = new LinkedHashMap<>(existing.getProperties());
        properties.putAll(incoming.getProperties());
        return new GraphRegisteredEntity(existing.getNodeId(), existing.getType(), existing.getCanonicalName(),
            new ArrayList<>(aliases), properties);
    }

    /**
     * 返回规范名称和全部别名。
     */
    private static List<String> names(GraphRegisteredEntity entity) {
        List<String> result = new ArrayList<>();
        result.add(entity.getCanonicalName());
        result.addAll(entity.getAliases());
        return result;
    }

    /**
     * 创建具有类型隔离的规范化索引键。
     */
    private static String key(String type, String name) {
        return normalize(type) + "\n" + normalize(name);
    }

    /**
     * 校验并规范 Space 名称，空值只用于兼容旧的无作用域 API。
     */
    private static String scope(String space) {
        return space == null ? "" : space.trim();
    }

    /**
     * 统一兼容字符、大小写和连续空白。
     */
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    }
}

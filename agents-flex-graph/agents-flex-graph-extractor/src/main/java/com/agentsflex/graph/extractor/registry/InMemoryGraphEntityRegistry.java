package com.agentsflex.graph.extractor.registry;

import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.identifier.GraphIdentifiers;

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
    public synchronized List<GraphRegisteredEntity> findMatches(String space, String type, Collection<String> names) {
        String scope = scope(space);
        if (type == null || type.trim().isEmpty()) throw new IllegalArgumentException("type must not be blank");
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
     * @return 当前注册实体数量，主要用于监控和测试。
     */
    public synchronized int size() {
        int total = 0;
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
     * 校验必填 Space 名称，拒绝未限定空间的实体操作。
     */
    private static String scope(String space) {
        return GraphIdentifiers.requireText(space, "space").trim();
    }

    /**
     * 统一兼容字符、大小写和连续空白。
     */
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    }
}

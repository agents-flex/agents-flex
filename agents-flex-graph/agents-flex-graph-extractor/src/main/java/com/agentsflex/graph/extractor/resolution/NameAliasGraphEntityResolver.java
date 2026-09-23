package com.agentsflex.graph.extractor.resolution;

import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按“节点类型 + 规范化名称/别名”执行确定性实体归一的默认实现。
 *
 * <p>该实现适合明确同名和别名，不尝试解决同名不同人或隐含指代。复杂业务可以替换
 * GraphEntityResolver，并结合向量检索、已有图谱或第二次模型判断。</p>
 */
public final class NameAliasGraphEntityResolver implements GraphEntityResolver {
    /**
     * 节点 ID 生成策略。
     */
    private final GraphEntityIdGenerator idGenerator;

    /**
     * 使用基于类型和规范名称的 SHA-256 稳定 ID 创建解析器。
     */
    public NameAliasGraphEntityResolver() {
        this(new HashGraphEntityIdGenerator());
    }

    /**
     * 使用自定义 ID 生成策略创建解析器。
     *
     * @param idGenerator 归一实体的最终节点 ID 生成策略
     */
    public NameAliasGraphEntityResolver(GraphEntityIdGenerator idGenerator) {
        if (idGenerator == null) throw new IllegalArgumentException("idGenerator must not be null");
        this.idGenerator = idGenerator;
    }

    /**
     * 按首次出现顺序合并名称、别名和属性。
     *
     * <p>名称或任一强别名相同且节点类型一致的候选会进入同一集合。一个新候选可能同时连接
     * 两个旧集合，此时执行传递合并。属性冲突采用首次值优先，保证结果与输入顺序确定。</p>
     */
    @Override
    public GraphEntityResolution resolve(List<GraphEntityCandidate> candidates) {
        if (candidates == null) throw new IllegalArgumentException("candidates must not be null");
        Map<String, Aggregate> aliasIndex = new LinkedHashMap<>();
        List<Aggregate> aggregates = new ArrayList<>();
        Map<String, Aggregate> candidateMappings = new LinkedHashMap<>();
        for (GraphEntityCandidate candidate : candidates) {
            if (candidate == null) throw new IllegalArgumentException("candidates must not contain null elements");
            Set<String> keys = keys(candidate);
            LinkedHashSet<Aggregate> matches = new LinkedHashSet<>();
            for (String key : keys) if (aliasIndex.containsKey(key)) matches.add(aliasIndex.get(key));
            Aggregate aggregate = matches.isEmpty() ? null : matches.iterator().next();
            if (aggregate == null) {
                aggregate = new Aggregate(candidate.getType(), candidate.getName());
                aggregates.add(aggregate);
            }
            // 一个新候选可能同时连接两个旧别名集合，此时必须把旧集合真正合并，避免留下重复节点。
            for (Aggregate other : matches) {
                if (other == aggregate) continue;
                aggregate.merge(other);
                aggregates.remove(other);
                replace(aliasIndex, other, aggregate);
                replace(candidateMappings, other, aggregate);
            }
            aggregate.merge(candidate);
            for (String key : keys) aliasIndex.put(key, aggregate);
            candidateMappings.put(candidate.getCandidateKey(), aggregate);
        }
        List<GraphNode> nodes = new ArrayList<>();
        Map<Aggregate, String> ids = new LinkedHashMap<>();
        for (Aggregate aggregate : aggregates) {
            String id = idGenerator.generate(aggregate.type, aggregate.name);
            ids.put(aggregate, id);
            nodes.add(GraphNode.builder(id, aggregate.type).properties(aggregate.properties).build());
        }
        Map<String, String> candidateToNode = new LinkedHashMap<>();
        for (Map.Entry<String, Aggregate> entry : candidateMappings.entrySet())
            candidateToNode.put(entry.getKey(), ids.get(entry.getValue()));
        return new GraphEntityResolution(nodes, candidateToNode);
    }

    /**
     * 收集“节点类型 + 名称/强别名”组成的全部规范化匹配键。
     */
    private static Set<String> keys(GraphEntityCandidate candidate) {
        Set<String> result = new LinkedHashSet<>();
        result.add(key(candidate.getType(), candidate.getName()));
        for (String alias : candidate.getAliases()) {
            if (!isWeakAlias(alias)) result.add(key(candidate.getType(), alias));
        }
        return result;
    }

    /**
     * 代词不能作为跨分段实体合并键，否则会把大量无关角色错误合并。
     */
    private static boolean isWeakAlias(String alias) {
        String value = HashGraphEntityIdGenerator.normalize(alias);
        return value.equals("他") || value.equals("她") || value.equals("它") || value.equals("他们")
            || value.equals("她们") || value.equals("它们") || value.equals("其") || value.equals("此人")
            || value.equals("该人") || value.equals("he") || value.equals("she") || value.equals("it")
            || value.equals("they") || value.equals("him") || value.equals("her") || value.equals("them");
    }

    /**
     * 生成包含节点类型的匹配键，避免不同类型的同名实体被错误合并。
     */
    private static String key(String type, String name) {
        return HashGraphEntityIdGenerator.normalize(type) + "\n" + HashGraphEntityIdGenerator.normalize(name);
    }

    /**
     * 把映射中指向旧聚合对象的值替换为合并后的主聚合对象。
     */
    private static <K> void replace(Map<K, Aggregate> values, Aggregate oldValue, Aggregate newValue) {
        for (Map.Entry<K, Aggregate> entry : values.entrySet()) {
            if (entry.getValue() == oldValue) entry.setValue(newValue);
        }
    }

    /**
     * 合并到单个 GraphNode 前使用的内部可变聚合状态。
     */
    private static final class Aggregate {
        /**
         * 聚合实体的 Schema 节点类型。
         */
        private final String type;
        /**
         * 首次出现的主名称。
         */
        private final String name;
        /**
         * 按候选出现顺序合并的属性。
         */
        private final Map<String, Object> properties = new LinkedHashMap<>();

        /**
         * 使用该集合中首次出现候选的类型和名称创建聚合状态。
         */
        private Aggregate(String type, String name) {
            this.type = type;
            this.name = name;
        }

        /**
         * 合并新候选时保留首次确认值，只补充此前缺失的属性。
         */
        private void merge(GraphEntityCandidate candidate) {
            for (Map.Entry<String, Object> entry : candidate.getProperties().entrySet()) {
                if (!properties.containsKey(entry.getKey())) properties.put(entry.getKey(), entry.getValue());
            }
        }

        /**
         * 合并两个别名集合时沿用同样的首次值优先策略。
         */
        private void merge(Aggregate other) {
            for (Map.Entry<String, Object> entry : other.properties.entrySet()) {
                if (!properties.containsKey(entry.getKey())) properties.put(entry.getKey(), entry.getValue());
            }
        }
    }
}

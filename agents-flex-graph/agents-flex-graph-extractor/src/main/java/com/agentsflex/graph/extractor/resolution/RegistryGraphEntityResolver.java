package com.agentsflex.graph.extractor.resolution;

import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 先执行本批名称/别名聚类，再查询持久化注册表的跨批次实体解析器。
 *
 * <p>同一候选聚类只命中一个既有节点时复用其稳定 ID；命中多个不同节点时说明别名存在歧义，
 * 解析器会失败并要求调用方审核，而不是把两个历史实体静默合并。</p>
 */
public final class RegistryGraphEntityResolver implements GraphEntityResolver {
    /**
     * 长期保存规范名称、别名与节点 ID 的注册表。
     */
    private final GraphEntityRegistry registry;
    /**
     * 没有命中既有实体时使用的新节点 ID 生成器。
     */
    private final GraphEntityIdGenerator idGenerator;

    /**
     * 使用默认哈希 ID 生成器创建解析器。
     */
    public RegistryGraphEntityResolver(GraphEntityRegistry registry) {
        this(registry, new HashGraphEntityIdGenerator());
    }

    /**
     * 使用指定注册表和新实体 ID 策略创建解析器。
     */
    public RegistryGraphEntityResolver(GraphEntityRegistry registry, GraphEntityIdGenerator idGenerator) {
        if (registry == null || idGenerator == null) {
            throw new IllegalArgumentException("registry and idGenerator must not be null");
        }
        this.registry = registry;
        this.idGenerator = idGenerator;
    }

    /**
     * 解析当前批次，并优先复用实体注册表中已经确认的节点身份。
     */
    @Override
    public GraphEntityResolution resolve(List<GraphEntityCandidate> candidates) {
        if (candidates == null) throw new IllegalArgumentException("candidates must not be null");
        List<Cluster> clusters = new ArrayList<>();
        Map<String, Cluster> localIndex = new LinkedHashMap<>();
        Map<String, Cluster> candidateClusters = new LinkedHashMap<>();
        for (GraphEntityCandidate candidate : candidates) {
            if (candidate == null) throw new IllegalArgumentException("candidates must not contain null elements");
            Set<String> keys = keys(candidate);
            LinkedHashSet<Cluster> matches = new LinkedHashSet<>();
            for (String key : keys) if (localIndex.containsKey(key)) matches.add(localIndex.get(key));
            Cluster cluster = matches.isEmpty() ? null : matches.iterator().next();
            if (cluster == null) {
                cluster = new Cluster(candidate.getType(), candidate.getName());
                clusters.add(cluster);
            }
            // 一个别名桥接多个本地聚类时执行传递合并，但不会在此合并两个注册表既有 ID。
            for (Cluster other : matches) {
                if (other == cluster) continue;
                cluster.merge(other);
                clusters.remove(other);
                replace(localIndex, other, cluster);
                replace(candidateClusters, other, cluster);
            }
            cluster.merge(candidate);
            for (String key : keys) localIndex.put(key, cluster);
            candidateClusters.put(candidate.getCandidateKey(), cluster);
        }

        List<GraphNode> nodes = new ArrayList<>();
        Map<Cluster, String> nodeIds = new LinkedHashMap<>();
        for (Cluster cluster : clusters) {
            List<GraphRegisteredEntity> registered = registry.find(cluster.type, cluster.names);
            GraphRegisteredEntity existing = uniqueMatch(cluster, registered);
            String nodeId = existing == null
                ? idGenerator.generate(cluster.type, cluster.canonicalName) : existing.getNodeId();
            Map<String, Object> properties = new LinkedHashMap<>();
            if (existing != null) properties.putAll(existing.getProperties());
            // 已有注册属性优先，当前抽取只补充缺失值，避免一次模型输出覆盖主数据。
            for (Map.Entry<String, Object> property : cluster.properties.entrySet()) {
                if (!properties.containsKey(property.getKey())) properties.put(property.getKey(), property.getValue());
            }
            nodes.add(GraphNode.builder(nodeId, cluster.type).properties(properties).build());
            nodeIds.put(cluster, nodeId);
        }
        Map<String, String> candidateToNode = new LinkedHashMap<>();
        for (Map.Entry<String, Cluster> mapping : candidateClusters.entrySet()) {
            candidateToNode.put(mapping.getKey(), nodeIds.get(mapping.getValue()));
        }
        return new GraphEntityResolution(nodes, candidateToNode);
    }

    /**
     * 返回唯一注册表命中；多个不同节点 ID 命中时拒绝自动消歧。
     */
    private static GraphRegisteredEntity uniqueMatch(Cluster cluster, List<GraphRegisteredEntity> matches) {
        if (matches == null || matches.isEmpty()) return null;
        GraphRegisteredEntity first = null;
        Set<String> ids = new LinkedHashSet<>();
        for (GraphRegisteredEntity match : matches) {
            if (match == null) continue;
            if (!cluster.type.equals(match.getType())) continue;
            ids.add(match.getNodeId());
            if (first == null) first = match;
        }
        if (ids.size() > 1) {
            throw new GraphExtractionException("Ambiguous registered entities for " + cluster.type
                + " names " + cluster.names + ": " + ids);
        }
        return first;
    }

    /**
     * 收集当前候选用于本批聚类的强名称键。
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
     * 排除代词等不具备稳定实体身份的弱别名。
     */
    private static boolean isWeakAlias(String alias) {
        String value = normalize(alias);
        return value.equals("他") || value.equals("她") || value.equals("它") || value.equals("他们")
            || value.equals("她们") || value.equals("它们") || value.equals("其") || value.equals("此人")
            || value.equals("该人") || value.equals("he") || value.equals("she") || value.equals("it")
            || value.equals("they") || value.equals("him") || value.equals("her") || value.equals("them");
    }

    /**
     * 创建类型隔离的本批聚类键。
     */
    private static String key(String type, String name) {
        return normalize(type) + "\n" + normalize(name);
    }

    /**
     * 使用与默认解析器一致的 Unicode、大小写和空白规范化语义。
     */
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 把映射中已经合并的旧聚类替换为主聚类。
     */
    private static <K> void replace(Map<K, Cluster> values, Cluster oldValue, Cluster newValue) {
        for (Map.Entry<K, Cluster> entry : values.entrySet()) {
            if (entry.getValue() == oldValue) entry.setValue(newValue);
        }
    }

    /**
     * 当前批次中通过强名称和别名形成的可变实体聚类。
     */
    private static final class Cluster {
        /**
         * Schema 节点类型。
         */
        private final String type;
        /**
         * 本批首次出现的主名称。
         */
        private final String canonicalName;
        /**
         * 注册表查询使用的全部名称和强别名。
         */
        private final Set<String> names = new LinkedHashSet<>();
        /**
         * 当前批次首次值优先的属性集合。
         */
        private final Map<String, Object> properties = new LinkedHashMap<>();

        private Cluster(String type, String canonicalName) {
            this.type = type;
            this.canonicalName = canonicalName;
        }

        /**
         * 合并一个候选的名称、别名和缺失属性。
         */
        private void merge(GraphEntityCandidate candidate) {
            names.add(candidate.getName());
            for (String alias : candidate.getAliases()) if (!isWeakAlias(alias)) names.add(alias);
            for (Map.Entry<String, Object> property : candidate.getProperties().entrySet()) {
                if (!properties.containsKey(property.getKey())) properties.put(property.getKey(), property.getValue());
            }
        }

        /**
         * 合并被别名桥接的另一个本地聚类。
         */
        private void merge(Cluster other) {
            names.addAll(other.names);
            for (Map.Entry<String, Object> property : other.properties.entrySet()) {
                if (!properties.containsKey(property.getKey())) properties.put(property.getKey(), property.getValue());
            }
        }
    }
}

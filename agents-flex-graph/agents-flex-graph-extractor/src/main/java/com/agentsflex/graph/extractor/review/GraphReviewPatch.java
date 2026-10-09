package com.agentsflex.graph.extractor.review;

import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.extractor.GraphExtractionResult;
import com.agentsflex.graph.extractor.GraphMutationMapper;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolution;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import com.agentsflex.graph.schema.GraphSchema;

import java.util.*;

/**
 * 对合法候选的不可变人工修改集合，不直接操作数据库。
 *
 * <p>关系使用当前结果 relations 列表的下标定位，必须与任务 reviewVersion 一起提交。
 * 拒绝实体会同时移除其关联关系；解析实体后 SDK 重新生成端点、Mutation、来源与注册记录。
 * 原始 allEntities/allRelations 和问题列表保持不变，便于追溯模型输出。</p>
 */
public final class GraphReviewPatch {
    /**
     * 要从批准候选中移除的实体键。
     */
    private final Set<String> rejectedEntities;
    /**
     * 当前版本 relations 列表中要移除的下标。
     */
    private final Set<Integer> rejectedRelations;
    /**
     * 人工确认的候选到最终节点映射。
     */
    private final Map<String, GraphNode> resolvedEntities;
    /**
     * 按候选键替换完整属性映射。
     */
    private final Map<String, Map<String, Object>> entityProperties;
    /**
     * 按关系下标替换完整属性映射。
     */
    private final Map<Integer, Map<String, Object>> relationProperties;

    private GraphReviewPatch(Builder b) {
        rejectedEntities = Collections.unmodifiableSet(new LinkedHashSet<>(b.rejectedEntities));
        rejectedRelations = Collections.unmodifiableSet(new LinkedHashSet<>(b.rejectedRelations));
        resolvedEntities = Collections.unmodifiableMap(new LinkedHashMap<>(b.resolvedEntities));
        entityProperties = Collections.unmodifiableMap(new LinkedHashMap<>(b.entityProperties));
        relationProperties = Collections.unmodifiableMap(new LinkedHashMap<>(b.relationProperties));
    }

    /**
     * @return 修改集合构造器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 修改批准候选并重新映射节点和边；保留证据，不重新调用模型。
     */
    GraphExtractionResult apply(GraphExtractionResult old, GraphSchema schema) {
        if (old == null || schema == null) throw new IllegalArgumentException("complete review context is required");
        Set<String> keys = new LinkedHashSet<>();
        for (GraphEntityCandidate e : old.getEntities()) keys.add(e.getCandidateKey());
        for (String key : rejectedEntities) requireKey(keys, key);
        for (String key : resolvedEntities.keySet()) requireKey(keys, key);
        for (String key : entityProperties.keySet()) requireKey(keys, key);
        for (Integer index : rejectedRelations) requireIndex(index, old.getRelations().size());
        for (Integer index : relationProperties.keySet()) requireIndex(index, old.getRelations().size());
        List<GraphEntityCandidate> entities = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Map<String, GraphNode> originalNodes = new LinkedHashMap<>();
        for (GraphNode n : old.getResolution().getNodes()) originalNodes.put(n.getId(), n);
        // 先保留原节点，再应用显式人工决策，避免遍历顺序覆盖人工修改。
        for (GraphEntityCandidate e : old.getEntities()) {
            if (rejectedEntities.contains(e.getCandidateKey())) continue;
            String id = old.getResolution().nodeId(e.getCandidateKey());
            GraphNode n = originalNodes.get(id);
            if (n == null) throw new IllegalArgumentException("resolved node is missing: " + e.getCandidateKey());
            mapping.put(e.getCandidateKey(), id);
            nodes.put(id, n);
        }
        Map<String, GraphNode> editedNodes = new LinkedHashMap<>();
        for (GraphEntityCandidate e : old.getEntities()) {
            String key = e.getCandidateKey();
            if (rejectedEntities.contains(key)) {
                if (resolvedEntities.containsKey(key) || entityProperties.containsKey(key))
                    throw new IllegalArgumentException("cannot reject and edit the same entity");
                continue;
            }
            GraphNode resolved = resolvedEntities.get(key);
            Map<String, Object> props = entityProperties.containsKey(key) ? entityProperties.get(key)
                : resolved != null ? resolved.getProperties() : e.getProperties();
            GraphSchema.NodeType definition = null;
            for (GraphSchema.NodeType type : schema.getNodeTypes())
                if (type.getLabel().equals(e.getType())) definition = type;
            if (definition == null) throw new IllegalArgumentException("unknown node type: " + e.getType());
            checkProperties(props, definition.getProperties());
            if (resolved != null && (resolved.getLabels().size() != 1 || !resolved.getLabels().contains(e.getType())))
                throw new IllegalArgumentException("resolved node type must match candidate type");
            String id = resolved == null ? mapping.get(key) : resolved.getId();
            if (resolved != null || entityProperties.containsKey(key)) {
                GraphNode replacement = GraphNode.builder(id, e.getType()).properties(props).build();
                GraphNode collision = nodes.get(id);
                if (collision != null && !collision.getLabels().contains(e.getType()))
                    throw new IllegalArgumentException("node identity is shared by incompatible types");
                GraphNode edit = editedNodes.get(id);
                if (edit != null && (!edit.getProperties().equals(props) || !edit.getLabels().equals(replacement.getLabels())))
                    throw new IllegalArgumentException("conflicting edits for the same resolved node");
                editedNodes.put(id, replacement);
                nodes.put(id, replacement);
            }
            mapping.put(key, id);
            entities.add(new GraphEntityCandidate(key, e.getName(), e.getType(), e.getAliases(), props,
                e.getEvidence(), e.getConfidence()));
        }
        nodes.keySet().retainAll(new LinkedHashSet<>(mapping.values()));
        List<GraphRelationCandidate> relations = new ArrayList<>();
        for (int i = 0; i < old.getRelations().size(); i++) {
            GraphRelationCandidate r = old.getRelations().get(i);
            if (rejectedRelations.contains(i) && relationProperties.containsKey(i))
                throw new IllegalArgumentException("cannot reject and edit the same relation");
            if (rejectedRelations.contains(i) || !mapping.containsKey(r.getSourceCandidateKey())
                || !mapping.containsKey(r.getTargetCandidateKey())) continue;
            Map<String, Object> props = relationProperties.containsKey(i) ? relationProperties.get(i) : r.getProperties();
            GraphSchema.EdgeType definition = null;
            for (GraphSchema.EdgeType type : schema.getEdgeTypes())
                if (type.getType().equals(r.getType())) definition = type;
            if (definition == null) throw new IllegalArgumentException("unknown edge type: " + r.getType());
            checkProperties(props, definition.getProperties());
            relations.add(new GraphRelationCandidate(r.getSourceCandidateKey(), r.getType(), r.getTargetCandidateKey(),
                r.getRank(), props, r.getEvidence(), r.getConfidence(), r.getAssertionType()));
        }
        GraphEntityResolution resolution = new GraphEntityResolution(new ArrayList<>(nodes.values()), mapping);
        return new GraphExtractionResult(old.getAllEntities(), old.getAllRelations(), entities, relations, resolution,
            new GraphMutationMapper().map(resolution, relations), old.getIssues(), old.getRawResponses());
    }

    /**
     * 人工审核仍须满足 Schema 的类型和必填约束。
     */
    private static void checkProperties(Map<String, Object> props, List<GraphSchema.Property> definitions) {
        String problem = SchemaGraphCandidateValidator.propertyProblem(props, definitions);
        if (problem != null) throw new IllegalArgumentException(problem);
    }

    /**
     * 拒绝失效的候选定位，不能悄悄忽略 UI 传来的错误身份。
     */
    private static void requireKey(Set<String> keys, String key) {
        if (!keys.contains(key)) throw new IllegalArgumentException("unknown accepted candidate: " + key);
    }

    /**
     * 下标仅适用于当前任务版本。
     */
    private static void requireIndex(int index, int size) {
        if (index < 0 || index >= size) throw new IllegalArgumentException("relation index out of range: " + index);
    }

    /**
     * 人工修改构造器。
     */
    public static final class Builder {
        /**
         * 拒绝的候选实体键。
         */
        private final Set<String> rejectedEntities = new LinkedHashSet<>();
        /**
         * 拒绝的关系下标。
         */
        private final Set<Integer> rejectedRelations = new LinkedHashSet<>();
        /**
         * 人工确认的最终节点。
         */
        private final Map<String, GraphNode> resolvedEntities = new LinkedHashMap<>();
        /**
         * 完整替换的实体属性。
         */
        private final Map<String, Map<String, Object>> entityProperties = new LinkedHashMap<>();
        /**
         * 完整替换的关系属性。
         */
        private final Map<Integer, Map<String, Object>> relationProperties = new LinkedHashMap<>();

        /**
         * 拒绝实体及其关联关系；不会删除图中的共享实体。
         */
        public Builder rejectEntity(String key) {
            rejectedEntities.add(text(key));
            return this;
        }

        /**
         * 拒绝当前 relations 列表中的关系。
         */
        public Builder rejectRelation(int index) {
            rejectedRelations.add(index);
            return this;
        }

        /**
         * 指定已有或新的稳定节点，传入完整标签及属性。
         */
        public Builder resolveEntity(String key, GraphNode node) {
            if (node == null) throw new IllegalArgumentException("node must not be null");
            resolvedEntities.put(text(key), node);
            return this;
        }

        /**
         * 替换实体完整属性，不修改候选身份和证据。
         */
        public Builder entityProperties(String key, Map<String, ?> properties) {
            entityProperties.put(text(key), properties(properties));
            return this;
        }

        /**
         * 替换关系完整属性，不修改端点和证据。
         */
        public Builder relationProperties(int index, Map<String, ?> properties) {
            relationProperties.put(index, properties(properties));
            return this;
        }

        /**
         * @return 不可变修改集合。
         */
        public GraphReviewPatch build() {
            return new GraphReviewPatch(this);
        }

        /**
         * 防御性复制请求属性。
         */
        private static Map<String, Object> properties(Map<String, ?> values) {
            if (values == null) throw new IllegalArgumentException("properties must not be null");
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /**
         * 校验实体定位键。
         */
        private static String text(String key) {
            if (key == null || key.trim().isEmpty())
                throw new IllegalArgumentException("candidate key must not be blank");
            return key.trim();
        }
    }
}

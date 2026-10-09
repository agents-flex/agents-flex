package com.agentsflex.graph.extractor.ingestion;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.execution.GraphExecutionContext;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.registry.GraphRegisteredEntity;
import com.agentsflex.graph.mutation.GraphMutation;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 为不可变入图执行计划生成确定性指纹。
 *
 * <p>指纹覆盖图路由、全部 mutation、待提交文档状态、事实来源和实体注册内容，但有意忽略
 * 超时、请求链 ID 与提交时间等非业务字段。这样进程重启后可以确认恢复的确实是同一份计划，
 * 避免相同 operationId 在同一 revision 上误绑定另一份大模型抽取结果。</p>
 */
final class GraphIngestionPlanFingerprint {
    /**
     * 工具类不允许实例化。
     */
    private GraphIngestionPlanFingerprint() {
    }

    /**
     * @return 计划业务内容的 SHA-256 十六进制摘要。
     */
    static String compute(GraphIngestionPlan plan) {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        Digester digest = new Digester();
        digest.add("status", plan.getStatus().name());
        digest.add("space", plan.getSpace());
        digest.add("documentId", plan.getDocumentId());
        appendOptions(digest, plan.getGraphOptions());
        appendMutation(digest, plan.getMutation());
        appendState(digest, plan.getNextState());
        for (GraphRegisteredEntity entity : plan.getEntityRegistrations()) {
            digest.add("registration.nodeId", entity.getNodeId());
            digest.add("registration.type", entity.getType());
            digest.add("registration.name", entity.getCanonicalName());
            digest.add("registration.aliases", canonical(entity.getAliases()));
            digest.add("registration.properties", canonical(entity.getProperties()));
        }
        return digest.finish();
    }

    /**
     * 将影响后端路由的写入选项纳入指纹。
     */
    private static void appendOptions(Digester digest, GraphOptions options) {
        digest.add("options.space", options == null ? null : options.getSpace());
        digest.add("options.readOnly", options == null ? null : Boolean.toString(options.isReadOnly()));
        GraphExecutionContext context = options == null ? null : options.getContext();
        if (context == null) return;
        digest.add("context.connection", context.getConnectionName());
        digest.add("context.tenant", context.getTenantId());
        digest.add("context.schema", context.getSchemaVersion());
        digest.add("context.attributes", canonical(context.getAttributes()));
    }

    /**
     * 将图数据库实际执行的全部变更纳入指纹。
     */
    private static void appendMutation(Digester digest, GraphMutation mutation) {
        digest.add("mutation.operationId", mutation.getOperationId());
        digest.add("mutation.detach", Boolean.toString(mutation.isDetachDeletedNodes()));
        for (GraphNode node : mutation.getNodes()) {
            digest.add("node.id", node.getId());
            List<String> labels = new ArrayList<>(node.getLabels());
            Collections.sort(labels);
            digest.add("node.labels", canonical(labels));
            digest.add("node.properties", canonical(node.getProperties()));
        }
        for (GraphEdge edge : mutation.getEdges()) {
            digest.add("edge.key", edge.getKey().portableId());
            digest.add("edge.properties", canonical(edge.getProperties()));
        }
        List<String> deletedNodes = new ArrayList<>(mutation.getDeleteNodeIds());
        Collections.sort(deletedNodes);
        digest.add("delete.nodes", canonical(deletedNodes));
        List<String> deletedEdges = new ArrayList<>();
        for (GraphEdgeKey key : mutation.getDeleteEdgeKeys()) deletedEdges.add(key.portableId());
        Collections.sort(deletedEdges);
        digest.add("delete.edges", canonical(deletedEdges));
    }

    /**
     * 将写图成功后要提交的业务状态和可审计来源纳入指纹。
     */
    private static void appendState(Digester digest, GraphDocumentState state) {
        if (state == null) {
            digest.add("state", null);
            return;
        }
        digest.add("state.status", state.getStatus().name());
        digest.add("state.revision", Long.toString(state.getRevision()));
        digest.add("state.operationId", state.getOperationId());
        digest.add("state.contentHash", state.getContentHash());
        digest.add("state.documentVersion", state.getDocumentVersion());
        digest.add("state.schemaVersion", state.getSchemaVersion());
        digest.add("state.extractionFingerprint", state.getExtractionFingerprint());
        digest.add("state.sourceUpdatedAt", Long.toString(state.getSourceUpdatedAtMillis()));
        digest.add("state.batchId", state.getBatchId());
        digest.add("state.nodeIds", canonicalSorted(state.getNodeIds()));
        digest.add("state.edgeKeys", canonicalEdgeKeys(state.getEdgeKeys()));
        digest.add("state.supersededEdges", canonicalEdgeKeys(state.getSupersededEdgeKeys()));
        for (GraphFactSource fact : state.getFactSources()) appendFact(digest, fact);
    }

    /**
     * 追加一条事实来源；不包含创建时间，避免同一计划重建时因时钟变化产生误冲突。
     */
    private static void appendFact(Digester digest, GraphFactSource fact) {
        digest.add("fact.id", fact.getFactId());
        digest.add("fact.operationId", fact.getOperationId());
        digest.add("fact.documentRevision", Long.toString(fact.getDocumentRevision()));
        digest.add("fact.edge", fact.getEdgeKey().portableId());
        digest.add("fact.confidence", Double.toString(fact.getConfidence()));
        digest.add("fact.assertion", fact.getAssertionType().name());
        digest.add("fact.properties", canonical(fact.getProperties()));
        GraphEvidence evidence = fact.getEvidence();
        digest.add("evidence.document", evidence.getDocumentId());
        digest.add("evidence.chunk", evidence.getChunkId());
        digest.add("evidence.quote", evidence.getQuote());
        digest.add("evidence.start", Integer.toString(evidence.getStartOffset()));
        digest.add("evidence.end", Integer.toString(evidence.getEndOffset()));
        digest.add("evidence.metadata", canonical(evidence.getMetadata()));
    }

    /**
     * 对无序节点 ID 集合排序后编码。
     */
    private static String canonicalSorted(Collection<String> values) {
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return canonical(sorted);
    }

    /**
     * 对无序边集合按可移植身份排序后编码。
     */
    private static String canonicalEdgeKeys(Collection<GraphEdgeKey> values) {
        List<String> sorted = new ArrayList<>();
        for (GraphEdgeKey value : values) sorted.add(value.portableId());
        Collections.sort(sorted);
        return canonical(sorted);
    }

    /**
     * 确定性编码常见属性类型。Map 按键排序，List 保留顺序，其他 Collection 按编码排序；
     * 未知业务类型同时写入类名和字符串值，避免不同 Java 类型出现简单文本碰撞。
     */
    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(((Map<?, ?>) value).entrySet());
            Collections.sort(entries, Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            List<String> encoded = new ArrayList<>();
            for (Map.Entry<?, ?> entry : entries) {
                encoded.add(frame(String.valueOf(entry.getKey())) + frame(canonical(entry.getValue())));
            }
            return "map" + frameList(encoded);
        }
        if (value instanceof List) {
            List<String> encoded = new ArrayList<>();
            for (Object item : (List<?>) value) encoded.add(canonical(item));
            return "list" + frameList(encoded);
        }
        if (value instanceof Collection) {
            List<String> encoded = new ArrayList<>();
            for (Object item : (Collection<?>) value) encoded.add(canonical(item));
            Collections.sort(encoded);
            return "collection" + frameList(encoded);
        }
        if (value.getClass().isArray()) {
            List<String> encoded = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) encoded.add(canonical(Array.get(value, i)));
            return "array" + frameList(encoded);
        }
        return value.getClass().getName() + frame(String.valueOf(value));
    }

    /**
     * 以字符长度边界编码列表，消除相邻值拼接歧义。
     */
    private static String frameList(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(frame(value));
        return result.toString();
    }

    /**
     * 以字符长度边界编码单值。
     */
    private static String frame(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }

    /**
     * 封装带长度边界的 SHA-256 流式计算。
     */
    private static final class Digester {
        /**
         * 当前摘要实例。
         */
        private final MessageDigest digest;

        /**
         * 创建 JVM 必须支持的 SHA-256 摘要器。
         */
        private Digester() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is not available", exception);
            }
        }

        /**
         * 追加字段名和值，二者均带 UTF-8 字节长度边界。
         */
        private void add(String name, String value) {
            update(name);
            update(value == null ? "<null>" : value);
        }

        /**
         * 追加一个带字节长度前缀的 UTF-8 字符串。
         */
        private void update(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
            digest.update((byte) 0);
        }

        /**
         * @return 固定 64 字符的小写十六进制摘要。
         */
        private String finish() {
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        }
    }
}

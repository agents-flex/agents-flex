package com.agentsflex.graph.extractor.ingestion;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.mutation.GraphMutation;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 恢复计划指纹的确定性和防篡改范围测试。
 */
public class GraphIngestionPlanFingerprintTest {
    /**
     * Map 和 Set 的插入顺序不同不应改变同一业务计划的指纹。
     */
    @Test
    public void equivalentUnorderedValuesShouldProduceSameFingerprint() {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("name", "林默");
        forward.put("level", 3L);
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("level", 3L);
        reverse.put("name", "林默");

        String first = GraphIngestionPlanFingerprint.compute(plan("operation-1", forward,
            new LinkedHashSet<>(Arrays.asList("node-a", "node-b")), 100L));
        String second = GraphIngestionPlanFingerprint.compute(plan("operation-1", reverse,
            new LinkedHashSet<>(Arrays.asList("node-b", "node-a")), 200L));

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    /**
     * GraphMutation 的 operationId 属于重放身份，修改后必须导致指纹变化。
     */
    @Test
    public void mutationOperationIdShouldBeCoveredByFingerprint() {
        String first = GraphIngestionPlanFingerprint.compute(plan("operation-1",
            Collections.<String, Object>singletonMap("name", "林默"),
            Collections.singleton("node-a"), 100L));
        String second = GraphIngestionPlanFingerprint.compute(plan("operation-2",
            Collections.<String, Object>singletonMap("name", "林默"),
            Collections.singleton("node-a"), 100L));

        assertFalse(first.equals(second));
    }

    /**
     * 节点属性、关系属性和事实 Evidence 任一变化都必须被检测。
     */
    @Test
    public void businessContentChangesShouldProduceDifferentFingerprint() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "林默");
        original.put("level", 3L);
        Map<String, Object> changed = new LinkedHashMap<>(original);
        changed.put("level", 4L);

        String first = GraphIngestionPlanFingerprint.compute(plan("operation-1", original,
            Collections.singleton("node-a"), 100L));
        String second = GraphIngestionPlanFingerprint.compute(plan("operation-1", changed,
            Collections.singleton("node-a"), 100L));

        assertFalse(first.equals(second));
    }

    /**
     * 提交时间属于运行时信息，同一业务计划反序列化后不应因时间不同产生冲突。
     */
    @Test
    public void runtimeTimestampsShouldNotChangeFingerprint() {
        String first = GraphIngestionPlanFingerprint.compute(plan("operation-1",
            Collections.<String, Object>singletonMap("name", "林默"),
            Collections.singleton("node-a"), 100L));
        String second = GraphIngestionPlanFingerprint.compute(plan("operation-1",
            Collections.<String, Object>singletonMap("name", "林默"),
            Collections.singleton("node-a"), 999L));

        assertEquals(first, second);
    }

    /**
     * 数据库存储按公开字段重建计划后必须得到完全相同的恢复指纹。
     */
    @Test
    public void restoredPlanShouldRetainFingerprint() {
        GraphIngestionPlan original = plan("operation-1",
            Collections.<String, Object>singletonMap("name", "林默"),
            Collections.singleton("node-a"), 100L);
        GraphIngestionPlan restored = GraphIngestionPlan.restore(original.getType(),
            original.getSpace(), original.getDocumentId(), original.getGraphOptions(), original.getPreviousState(),
            original.getNextState(), null, original.getMutation(), original.getStaleEdgeKeys(),
            original.getEntityRegistrations());

        assertEquals(GraphIngestionPlanFingerprint.compute(original),
            GraphIngestionPlanFingerprint.compute(restored));
    }

    /**
     * 创建覆盖节点、关系、状态和来源记录的可恢复计划。
     */
    private static GraphIngestionPlan plan(String operationId, Map<String, Object> nodeProperties,
                                                      java.util.Set<String> nodeIds, long committedAtMillis) {
        GraphNode person = GraphNode.builder("node-a", "Character").properties(nodeProperties).build();
        GraphNode organization = GraphNode.builder("node-b", "Organization").property("name", "青云会").build();
        GraphEdge edge = GraphEdge.builder("node-a", "MEMBER_OF", "node-b").property("chapter", 1L).build();
        GraphEvidence evidence = new GraphEvidence("doc-1", "chunk-1", "林默加入青云会", 0, 7,
            Collections.<String, Object>singletonMap("page", 1L));
        GraphFactSource fact = new GraphFactSource("fact-1", operationId, 1L, committedAtMillis,
            edge.getKey(), evidence, 0.95D, GraphAssertionType.EXPLICIT,
            Collections.<String, Object>singletonMap("chapter", 1L));
        GraphDocumentState next = GraphDocumentState.builder("knowledge", "doc-1", "content-hash")
            .revision(1L).operationId(operationId).documentVersion("v1").schemaVersion("schema-v1")
            .extractionFingerprint("extractor-v1").sourceUpdatedAtMillis(10L).batchId("batch-1")
            .committedAtMillis(committedAtMillis).nodeIds(nodeIds).edgeKey(edge.getKey()).factSource(fact).build();
        GraphMutation mutation = GraphMutation.builder().operationId(operationId)
            .upsertNodes(Arrays.asList(person, organization)).upsertEdge(edge)
            .deleteEdge(new GraphEdgeKey("old-a", "MEMBER_OF", "old-b", 0L)).build();
        return GraphIngestionPlan.restore(GraphIngestionPlan.Type.INGESTION, "knowledge", "doc-1",
            GraphOptions.ofSpace("knowledge"), null, next, null, mutation,
            Collections.singleton(edge.getKey()), Collections.emptyList());
    }
}

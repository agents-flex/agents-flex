package com.agentsflex.graph;

import com.agentsflex.graph.capability.GraphCapabilities;
import com.agentsflex.graph.connection.GraphConnectionRegistry;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.capability.GraphFeature;
import com.agentsflex.graph.connection.GraphHealth;
import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaInspection;
import com.agentsflex.graph.transaction.GraphTransactionManager;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.query.NativeGraphQuery;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 Graph API 数据对象的构造、校验和不可变性。
 *
 * <p>这些测试不连接任何外部数据库，专门锁定跨后端契约，避免某个适配器偷偷改变
 * 节点、边或 Schema 的语义。</p>
 */
public class GraphModelTest {
    @Test
    public void nodeAndEdgeShouldKeepValidatedImmutableSnapshots() {
        Map<String, Object> nodeProperties = new LinkedHashMap<>();
        nodeProperties.put("name", "Alice");
        GraphNode node = GraphNode.builder("user-1", "Person")
            .label("Employee")
            .properties(nodeProperties)
            .build();
        nodeProperties.put("name", "Changed");

        assertEquals("Alice", node.getProperties().get("name"));
        assertEquals(Arrays.asList("Person", "Employee"), node.getLabelList());
        assertUnmodifiable(node.getProperties());
        assertUnmodifiable(node.getLabels());

        GraphEdge edge = GraphEdge.builder("user-1", "KNOWS", "user-2")
            .rank(3)
            .property("since", 2024)
            .build();
        assertEquals(3, edge.getRank());
        assertEquals("user-1\u001fKNOWS\u001fuser-2\u001f3", edge.getKey().portableId());
        assertUnmodifiable(edge.getProperties());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nodeShouldRequireAtLeastOneLabel() {
        GraphNode.builder("user-1", null).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void edgeShouldRejectBlankEndpoint() {
        new GraphEdgeKey(" ", "KNOWS", "user-2", 0);
    }

    @Test
    public void schemaShouldFreezeNestedDefinitions() {
        GraphSchema.Property id = new GraphSchema.Property("id", GraphSchema.PropertyType.STRING, true);
        GraphSchema.NodeType person = GraphSchema.NodeType.of("Person", id);
        GraphSchema.EdgeType knows = GraphSchema.EdgeType.of("KNOWS", "Person", "Person");
        GraphSchema.Index index = new GraphSchema.Index("person_name", GraphSchema.IndexTarget.NODE,
            "Person", Collections.singletonList("name"), false);
        GraphSchema schema = GraphSchema.builder().nodeType(person).edgeType(knows).index(index).build();

        assertSame(person, schema.getNodeTypes().get(0));
        assertEquals("Person", schema.getNodeTypes().get(0).getLabel());
        assertEquals("KNOWS", schema.getEdgeTypes().get(0).getType());
        assertEquals("name", schema.getIndexes().get(0).getProperties().get(0));
        assertUnmodifiable(schema.getNodeTypes());
        assertUnmodifiable(schema.getNodeTypes().get(0).getProperties());
        assertUnmodifiable(schema.getIndexes().get(0).getProperties());
    }

    @Test
    public void optionsAndNativeQueryShouldPreserveExplicitValues() {
        GraphOptions options = GraphOptions.builder().space("tenant_a").timeoutMillis(1500)
            .fetchSize(25).maxRecords(50).build();
        assertEquals("tenant_a", options.getSpaceOrDefault("fallback"));
        assertEquals(1500, options.getTimeoutMillis());
        assertEquals(25, options.getFetchSize());
        assertEquals(50, options.getMaxRecords());
        assertEquals("fallback", GraphOptions.DEFAULT.getSpaceOrDefault("fallback"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("name", "Alice");
        NativeGraphQuery query = NativeGraphQuery.of("MATCH (n) WHERE n.name = $name RETURN n", parameters);
        parameters.put("name", "Bob");
        assertEquals("Alice", query.getParameters().get("name"));
        assertUnmodifiable(query.getParameters());
    }

    @Test
    public void resultShouldExposeExecutionMetadataAndInspectionWarnings() {
        GraphResult result = new GraphResult(Collections.singletonList(
            new GraphRecord(Collections.<String, Object>singletonMap("name", "Alice"))),
            "MATCH (n)", new GraphResultMetadata(1, true, 12L));
        assertEquals(1, result.getMetadata().getRecordCount());
        assertTrue(result.getMetadata().isTruncated());
        assertEquals(12L, result.getMetadata().getExecutionTimeMillis());

        GraphSchema inspected = GraphSchema.builder().edgeType(GraphSchema.EdgeType.any("KNOWS")).build();
        GraphSchemaInspection inspection = new GraphSchemaInspection(inspected, false,
            Collections.singletonList("endpoint labels unavailable"));
        assertFalse(inspection.isComplete());
        assertEquals(null, inspection.getSchema().getEdgeTypes().get(0).getSourceLabel());
        assertEquals(1, inspection.getWarnings().size());
        assertUnmodifiable(inspection.getWarnings());
    }

    @Test(expected = IllegalArgumentException.class)
    public void optionsShouldRejectNonPositiveMaxRecords() {
        GraphOptions.builder().maxRecords(0);
    }

    @Test
    public void capabilitiesShouldExposeReadOnlyFeatureSet() {
        GraphCapabilities capabilities = GraphCapabilities.of(GraphFeature.SCHEMA, GraphFeature.INDEX);
        assertTrue(capabilities.supports(GraphFeature.SCHEMA));
        assertFalse(capabilities.supports(GraphFeature.TRANSACTIONS));
        assertUnmodifiable(capabilities.asSet());
        try {
            capabilities.require(GraphFeature.TRANSACTIONS);
        } catch (UnsupportedGraphFeatureException expected) {
            assertTrue(expected.getMessage().contains("TRANSACTIONS"));
            return;
        }
        throw new AssertionError("require should reject an unsupported feature");
    }

    @Test
    public void identifiersShouldExposeNonThrowingPortabilityCheck() {
        assertTrue(GraphIdentifiers.isValid("Person_1"));
        assertFalse(GraphIdentifiers.isValid("person-name"));
        assertFalse(GraphIdentifiers.isValid(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void schemaShouldRejectDuplicateNodeLabels() {
        GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person"))
            .nodeType(GraphSchema.NodeType.of("Person"))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void schemaShouldRejectDuplicatePropertiesInOneType() {
        GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person",
                new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
                new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false)))
            .build();
    }

    @Test
    public void nonOwningRegistryShouldNotCloseSpringManagedStores() {
        GraphConnectionRegistry registry = new GraphConnectionRegistry(false);
        StubStore store = new StubStore("spring");
        registry.register("spring", store);
        assertTrue(registry.remove("spring"));
        assertFalse(store.closed);
        registry.register("spring", store);
        registry.close();
        assertFalse(store.closed);
    }

    @Test
    public void writeResultAndRecordShouldExposeSuccessAndFailureDetails() {
        GraphWriteResult success = GraphWriteResult.success(2, 4);
        assertTrue(success.isSuccess());
        assertEquals(2, success.getNodesAffected());
        assertEquals(4, success.getEdgesAffected());

        IllegalStateException cause = new IllegalStateException("backend down");
        GraphWriteResult failure = GraphWriteResult.failure(null, cause);
        assertFalse(failure.isSuccess());
        assertEquals("", failure.getMessage());
        assertSame(cause, failure.getError());

        GraphRecord record = new GraphRecord(Collections.<String, Object>singletonMap("name", "Alice"));
        assertEquals("Alice", record.get("name"));
        assertUnmodifiable(record.getValues());
    }

    /**
     * 用于覆盖注册表生命周期策略的最小 GraphStore 替身。
     */
    private static final class StubStore implements GraphStore {
        private final String backend;
        private boolean closed;

        private StubStore(String backend) {
            this.backend = backend;
        }

        @Override
        public GraphCapabilities capabilities() {
            return GraphCapabilities.none();
        }

        @Override
        public GraphManager manager() {
            return null;
        }

        @Override
        public GraphWriter writer() {
            return null;
        }

        @Override
        public GraphQueryExecutor query() {
            return null;
        }

        @Override
        public GraphTransactionManager transactions() {
            return null;
        }

        @Override
        public GraphHealth health() {
            return GraphHealth.up(backend, 1L);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static void assertUnmodifiable(Object collection) {
        try {
            if (collection instanceof Map) {
                ((Map<?, ?>) collection).clear();
            } else if (collection instanceof java.util.Set) {
                ((java.util.Set<?>) collection).clear();
            } else if (collection instanceof java.util.List) {
                ((java.util.List<?>) collection).clear();
            } else {
                throw new AssertionError("unsupported collection type: " + collection.getClass());
            }
            throw new AssertionError("collection should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // 只读快照符合 API 契约。
        }
    }
}

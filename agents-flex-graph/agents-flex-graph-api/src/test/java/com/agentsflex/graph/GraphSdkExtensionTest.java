package com.agentsflex.graph;

import com.agentsflex.graph.capability.GraphCapabilities;
import com.agentsflex.graph.capability.GraphFeature;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.execution.GraphExecutionContext;
import com.agentsflex.graph.importing.GraphImportError;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultCursor;
import com.agentsflex.graph.query.GraphResultCursors;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphPageRequest;
import com.agentsflex.graph.query.GraphPageResult;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.query.GraphSubgraphResult;
import com.agentsflex.graph.query.GraphQueryKind;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.schema.GraphElementMetadata;
import com.agentsflex.graph.schema.GraphPropertyMetadata;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaApplyResult;
import com.agentsflex.graph.schema.GraphSchemaMetadata;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.manager.GraphSchemaManager;
import com.agentsflex.graph.manager.GraphSpaceManager;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证面向开发工具的 Schema、查询、错误和执行上下文扩展契约。
 */
public class GraphSdkExtensionTest {
    @Test
    public void schemaShouldExposeToolingMetadataAndApplyResult() {
        GraphSchema schema = GraphSchema.builder()
            .metadata(new GraphSchemaMetadata("customer", "v2", "客户图", "业务关系图", null))
            .nodeType(GraphSchema.NodeType.of("Person", new GraphElementMetadata("人物", "客户或联系人"),
                new GraphSchema.Property("status", GraphSchema.PropertyType.STRING, true,
                    new GraphPropertyMetadata("状态", "当前状态", "active", Arrays.asList("active", "disabled")))))
            .build();

        assertEquals("v2", schema.getMetadata().getVersion());
        assertEquals("人物", schema.getNodeTypes().get(0).getMetadata().getDisplayName());
        assertEquals("active", schema.getNodeTypes().get(0).getProperties().get(0).getMetadata().getDefaultValue());

        GraphSchemaApplyResult result = GraphSchemaApplyResult.failure(
            new GraphException(GraphErrorCode.SCHEMA_APPLY_FAILED, "permission denied"), 3L);
        assertFalse(result.isSuccess());
        assertEquals(GraphErrorCode.SCHEMA_APPLY_FAILED, result.getErrorCode());
        assertEquals(3L, result.getExecutionTimeMillis());
    }

    @Test
    public void capabilitiesShouldExposeLimitsAndModes() {
        GraphCapabilities capabilities = GraphCapabilities.of(GraphFeature.VARIABLE_LENGTH_PATH)
            .withLimit(GraphFeature.VARIABLE_LENGTH_PATH, "maxHops", "16")
            .withMode(GraphFeature.VARIABLE_LENGTH_PATH, "PORTABLE");
        assertEquals("16", capabilities.describe(GraphFeature.VARIABLE_LENGTH_PATH).getLimits().get("maxHops"));
        assertEquals(Collections.singletonList("PORTABLE"), capabilities.describe(GraphFeature.VARIABLE_LENGTH_PATH).getModes());
        assertEquals("not installed", GraphCapabilities.none()
            .withNote(GraphFeature.NATIVE_QUERY, "not installed")
            .describe(GraphFeature.NATIVE_QUERY).getNote());
    }

    @Test
    public void contextAndNativeQueryShouldCarryExecutionIntent() {
        GraphExecutionContext context = GraphExecutionContext.builder()
            .connectionName("prod")
            .tenantId("tenant-a")
            .requestId("req-1")
            .schemaVersion("v2")
            .attribute("operator", "alice")
            .build();
        GraphOptions options = GraphOptions.builder().context(context).readOnly(true).build();
        NativeGraphQuery query = NativeGraphQuery.of("CREATE (n)", Collections.<String, Object>emptyMap(), GraphQueryKind.WRITE);
        assertEquals("tenant-a", options.getContext().getTenantId());
        assertTrue(options.isReadOnly());
        assertFalse(query.isReadOnly());
        assertEquals(GraphQueryKind.WRITE, query.getKind());
    }

    @Test
    public void graphResultShouldExposeSubgraphAndCursor() {
        GraphNode node = GraphNode.builder("u1", "Person").build();
        GraphEdge edge = GraphEdge.builder("u1", "KNOWS", "u2").build();
        GraphSubgraphResult subgraph = new GraphSubgraphResult(Collections.singletonList(node),
            Collections.singletonList(edge), new GraphResultMetadata(2, false, 1L));
        GraphResult result = new GraphResult(Collections.emptyList(), "MATCH", new GraphResultMetadata(0, false, 1L), subgraph);
        assertNotNull(result.getSubgraph());
        GraphResultCursor cursor = GraphResultCursors.of(new GraphResult(
            Collections.singletonList(new GraphRecord(Collections.<String, Object>singletonMap("id", "u1"))), "MATCH"));
        assertTrue(cursor.hasNext());
        assertEquals("u1", cursor.next().get("id"));
        cursor.close();
        assertFalse(cursor.hasNext());
    }

    @Test
    public void subgraphResultShouldDeduplicateStableEntityIdentities() {
        GraphNode node = GraphNode.builder("u1", "Person").build();
        GraphEdge edge = GraphEdge.builder("u1", "KNOWS", "u2").build();
        GraphSubgraphResult subgraph = new GraphSubgraphResult(Arrays.asList(node, node),
            Arrays.asList(edge, edge), null);
        assertEquals(1, subgraph.getNodes().size());
        assertEquals(1, subgraph.getEdges().size());
    }

    @Test
    public void importReportShouldExposeStructuredBatchErrors() {
        GraphImportReport report = new GraphImportReport();
        report.add(7, GraphWriteResult.failure("bad row", new GraphException(GraphErrorCode.IMPORT_BATCH_FAILED, "bad row")));
        assertEquals(1, report.getErrorDetails().size());
        assertEquals(1, report.getBatchesFailed());
        assertEquals(1, report.getBatchesAttempted());
        GraphImportError error = report.getErrorDetails().get(0);
        assertEquals(7, error.getBatchIndex());
        assertEquals(GraphErrorCode.IMPORT_BATCH_FAILED, error.getCode());
    }

    @Test
    public void managerShouldExposeNarrowSpaceAndSchemaViews() {
        GraphManager manager = new GraphManager() {
            @Override
            public void createSpace(com.agentsflex.graph.manager.GraphSpaceDefinition definition,
                                    CreateMode mode) {
            }

            @Override
            public boolean spaceExists(String name) {
                return false;
            }

            @Override
            public java.util.List<String> listSpaces() {
                return Collections.emptyList();
            }

            @Override
            public void dropSpace(String name) {
            }

            @Override
            public void applySchema(String space, GraphSchema schema, SchemaMode mode) {
            }

            @Override
            public com.agentsflex.graph.schema.GraphSchemaValidation validateSchema(
                String space, GraphSchema schema) {
                return com.agentsflex.graph.schema.GraphSchemaValidation.valid();
            }
        };
        GraphSpaceManager spaces = manager.spaces();
        GraphSchemaManager schemas = manager.schema();
        assertTrue(spaces == manager);
        assertTrue(schemas == manager);
    }

    @Test
    public void pageRequestShouldExposeOpaqueNextCursor() {
        final GraphResult full = new GraphResult(Arrays.asList(
            new GraphRecord(Collections.<String, Object>singletonMap("id", "1")),
            new GraphRecord(Collections.<String, Object>singletonMap("id", "2"))), "MATCH",
            new GraphResultMetadata(2, true, 1L));
        GraphQueryExecutor executor = new GraphQueryExecutor() {
            @Override
            public GraphResult execute(com.agentsflex.graph.query.GraphQuery query, GraphOptions options) {
                return full;
            }

            @Override
            public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
                return full;
            }
        };
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n")).build();
        GraphPageResult page = executor.executePage(query, GraphPageRequest.of(10, 2));
        assertTrue(page.hasNext());
        assertEquals("offset:12", page.getNextCursor());
        assertEquals("offset:12", page.getResult().getMetadata().getNextCursor());
        assertEquals(12, GraphPageRequest.after(page.getNextCursor(), 2).getOffset());
        assertEquals("opaque-token", GraphPageRequest.after("opaque-token", 2).getCursor());
    }

    @Test
    public void defaultPagingShouldFetchOneLookAheadRecord() {
        final TraversalQuery[] received = new TraversalQuery[1];
        GraphQueryExecutor executor = new GraphQueryExecutor() {
            @Override
            public GraphResult execute(com.agentsflex.graph.query.GraphQuery query, GraphOptions options) {
                received[0] = (TraversalQuery) query;
                java.util.List<GraphRecord> rows = Arrays.asList(
                    new GraphRecord(Collections.<String, Object>singletonMap("id", "1")),
                    new GraphRecord(Collections.<String, Object>singletonMap("id", "2")),
                    new GraphRecord(Collections.<String, Object>singletonMap("id", "3")));
                return new GraphResult(rows, "MATCH", new GraphResultMetadata(rows.size(), false, 1L));
            }

            @Override
            public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
                throw new UnsupportedOperationException();
            }
        };
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n"))
            .select(TraversalQuery.Projection.entity("n")).build();
        GraphPageResult page = executor.executePage(query, GraphPageRequest.of(0, 2));
        assertEquals(3, received[0].getLimit());
        assertEquals(2, page.getResult().getRecords().size());
        assertTrue(page.hasNext());
        assertEquals("offset:2", page.getNextCursor());
    }
}

package com.agentsflex.graph.tools;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.query.GraphQuery;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.query.GraphSubgraphResult;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.schema.GraphElementMetadata;
import com.agentsflex.graph.schema.GraphPropertyMetadata;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaMetadata;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 渐进式知识图谱 Tool 的契约测试。
 *
 * <p>测试覆盖 Tool 结构、Schema 分层披露、查询白名单、只读执行、分页游标、结果脱敏和
 * ToolScanner 参数转换，确保安全限制在访问真实数据库前生效。</p>
 */
public class KnowledgeGraphToolsTest {
    @Test
    public void shouldBuildThreeProgressiveDisclosureTools() {
        StubExecutor executor = new StubExecutor();
        KnowledgeGraphSource source = source(executor);
        List<Tool> tools = KnowledgeGraphTools.builder().addSource(source).buildTools();

        assertEquals(3, tools.size());
        Tool list = tool(tools, "listKnowledgeGraphTypes");
        assertNotNull(tool(tools, "describeKnowledgeGraphTypes"));
        assertNotNull(tool(tools, "queryKnowledgeGraph"));
        assertTrue(list.getDescription().contains("company_knowledge"));
        assertTrue(list.getDescription().contains("People &amp; companies"));
        assertEquals("company_knowledge", list.getParameters()[0].getEnums()[0]);

        String result = (String) list.invoke(Collections.<String, Object>singletonMap("knowledgeSourceName", "company_knowledge"));
        assertTrue(result.contains("Person"));
        assertTrue(result.contains("WORKS_AT"));
        assertFalse(result.contains("\"properties\""));
        assertFalse(result.contains("secret"));
    }

    @Test
    public void shouldDescribeOnlyRequestedSchemaElements() {
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(new StubExecutor())));

        String result = tools.describeKnowledgeGraphTypes(
            "company_knowledge",
            Collections.singletonList("Person"),
            Collections.<String>emptyList());

        assertTrue(result.contains("\"name\":\"Person\""));
        assertTrue(result.contains("\"name\":\"status\""));
        assertTrue(result.contains("ACTIVE"));
        assertFalse(result.contains("Company name"));
        assertFalse(result.contains("WORKS_AT"));
    }

    @Test
    public void shouldRejectUnknownAndExcessiveSchemaDisclosure() {
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(new StubExecutor())), 1);

        assertTrue(
            tools.describeKnowledgeGraphTypes("company_knowledge", Collections.singletonList("Missing"), Collections.<String>emptyList())
                .startsWith("Error: UNKNOWN_GRAPH_TYPE:"));
        assertTrue(
            tools.describeKnowledgeGraphTypes(
                "company_knowledge",
                Collections.singletonList("Person"),
                Collections.singletonList("WORKS_AT")).startsWith("Error: SCHEMA_DISCLOSURE_LIMIT:"));
    }

    @Test
    public void shouldExecuteReadOnlyPagedQueryAndFilterUnexposedProperties() {
        StubExecutor executor = new StubExecutor();
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(executor)));
        Map<String, Object> parameters = Collections.<String, Object>singletonMap("status", "ACTIVE");

        String result = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person)-[r:WORKS_AT]->(c:Company) " + "WHERE p.status = :status RETURN p, c.name AS company ORDER BY c.name",
            parameters,
            1,
            null);

        assertFalse(result, result.startsWith("Error:"));
        assertTrue(result.contains("Alice"));
        assertFalse(result.contains("hidden-value"));
        assertFalse(result.contains("backend query must not be returned"));
        assertTrue(result.contains("nextCursor"));
        assertNotNull(executor.receivedQuery);
        assertEquals(2, executor.receivedQuery.getLimit());
        assertTrue(executor.receivedOptions.isReadOnly());
        assertEquals("company_space", executor.receivedOptions.getSpace());
        assertEquals(2, executor.receivedOptions.getMaxRecords());
        assertEquals(10_000L, executor.receivedOptions.getTimeoutMillis());
    }

    @Test
    public void shouldRejectQueriesOutsideExposedSchemaBeforeExecution() {
        StubExecutor executor = new StubExecutor();
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(executor)));

        String unknownProperty = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person) WHERE p.secret = :value RETURN p",
            Collections.<String, Object>singletonMap("value", "x"),
            null,
            null);
        String untypedNode = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p) RETURN p",
            Collections.<String, Object>emptyMap(),
            null,
            null);
        String optional = tools.queryKnowledgeGraph(
            "company_knowledge",
            "OPTIONAL MATCH (p:Person) RETURN p",
            Collections.<String, Object>emptyMap(),
            null,
            null);

        assertTrue(unknownProperty.startsWith("Error: QUERY_NOT_ALLOWED:"));
        assertTrue(untypedNode.startsWith("Error: QUERY_NOT_ALLOWED:"));
        assertTrue(optional.startsWith("Error: UNSUPPORTED_QUERY:"));
        assertEquals(0, executor.executions);
    }

    @Test
    public void shouldRejectUnionWritesAndNativeDialectBeforeExecution() {
        StubExecutor executor = new StubExecutor();
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(executor)));

        String union = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person) RETURN p AS item UNION MATCH (c:Company) RETURN c AS item",
            Collections.<String, Object>emptyMap(),
            null,
            null);
        String write = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person) RETURN p DELETE p",
            Collections.<String, Object>emptyMap(),
            null,
            null);
        String nativeDialect = tools.queryKnowledgeGraph(
            "company_knowledge",
            "CALL db.labels()",
            Collections.<String, Object>emptyMap(),
            null,
            null);

        assertTrue(union.startsWith("Error: UNSUPPORTED_QUERY:"));
        assertTrue(write.startsWith("Error: INVALID_QUERY:"));
        assertTrue(nativeDialect.startsWith("Error: INVALID_QUERY:"));
        assertEquals(0, executor.executions);
    }

    @Test
    public void shouldFilterNodesAndEdgesInsidePathResults() {
        GraphQueryExecutor executor = new GraphQueryExecutor() {
            @Override
            public GraphResult execute(GraphQuery query, GraphOptions options) {
                GraphNode person = GraphNode.builder("person-1", "Person")
                    .property("name", "Alice")
                    .property("secret", "hidden-node-value")
                    .build();
                GraphNode company = GraphNode.builder("company-1", "Company")
                    .property("name", "Acme")
                    .property("secret", "hidden-company-value")
                    .build();
                GraphEdge employment = GraphEdge.builder("person-1", "WORKS_AT", "company-1")
                    .property("since", "2024-01-01")
                    .property("secret", "hidden-edge-value")
                    .build();
                GraphSubgraphResult path = new GraphSubgraphResult(Arrays.asList(person, company), Collections.singletonList(employment),
                    null);
                return new GraphResult(
                    Collections.singletonList(new GraphRecord(Collections.<String, Object>singletonMap("matchedPath", path))),
                    "backend query", null);
            }

            @Override
            public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
                throw new AssertionError("native queries must never be used by knowledge graph tools");
            }
        };
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(executor)));

        String result = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person)-[:WORKS_AT]->(c:Company) RETURN PATH AS matchedPath",
            Collections.<String, Object>emptyMap(),
            null,
            null);

        assertFalse(result, result.startsWith("Error:"));
        assertTrue(result.contains("Alice"));
        assertTrue(result.contains("Acme"));
        assertTrue(result.contains("2024-01-01"));
        assertFalse(result.contains("hidden-node-value"));
        assertFalse(result.contains("hidden-company-value"));
        assertFalse(result.contains("hidden-edge-value"));
        assertFalse(result.contains("backend query"));
    }

    @Test
    public void shouldRejectRelationshipWithWrongEndpointDirection() {
        StubExecutor executor = new StubExecutor();
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(executor)));

        String result = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (c:Company)-[:WORKS_AT]->(p:Person) RETURN p",
            Collections.<String, Object>emptyMap(),
            null,
            null);

        assertTrue(result.startsWith("Error: QUERY_NOT_ALLOWED:"));
        assertEquals(0, executor.executions);
    }

    @Test
    public void shouldEnforceHopPageAndPaginationRules() {
        StubExecutor executor = new StubExecutor();
        KnowledgeGraphSource source = KnowledgeGraphSource.builder("company_knowledge", executor, schema())
            .description("People & companies")
            .space("company_space")
            .maxHops(1)
            .defaultPageSize(5)
            .maxPageSize(5)
            .build();
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source));

        String hops = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person)-[:KNOWS*1..2]->(f:Person) RETURN f",
            Collections.<String, Object>emptyMap(),
            null,
            null);
        String page = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person) RETURN p",
            Collections.<String, Object>emptyMap(),
            6,
            null);
        String inlineLimit = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person) RETURN p LIMIT 2",
            Collections.<String, Object>emptyMap(),
            null,
            null);

        assertTrue(hops.startsWith("Error: QUERY_NOT_ALLOWED:"));
        assertTrue(page.startsWith("Error: INVALID_ARGUMENT:"));
        assertTrue(inlineLimit.startsWith("Error: QUERY_NOT_ALLOWED:"));
        assertEquals(0, executor.executions);
    }

    @Test
    public void shouldBindCursorToTheSameQuery() {
        StubExecutor executor = new StubExecutor();
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(executor)));
        String first = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (p:Person) RETURN p",
            Collections.<String, Object>emptyMap(),
            1,
            null);
        String cursor = JSON.parseObject(first).getJSONObject("metadata").getString("nextCursor");

        String second = tools.queryKnowledgeGraph(
            "company_knowledge",
            "MATCH (c:Company) RETURN c",
            Collections.<String, Object>emptyMap(),
            1,
            cursor);

        assertTrue(second.startsWith("Error: INVALID_ARGUMENT:"));
    }

    @Test
    public void shouldExposeMapParameterAsJsonObject() {
        List<Tool> tools = KnowledgeGraphTools.builder().addSource(source(new StubExecutor())).buildTools();
        Tool query = tool(tools, "queryKnowledgeGraph");
        Parameter parameters = parameter(query, "parameters");

        assertEquals("object", parameters.getType());
        assertTrue(parameters.isRequired());
    }

    @Test
    public void shouldInvokeQueryThroughScannedToolWithJsonArguments() {
        StubExecutor executor = new StubExecutor();
        Tool query = tool(KnowledgeGraphTools.builder().addSource(source(executor)).buildTools(), "queryKnowledgeGraph");
        JSONObject arguments = JSON.parseObject(
            "{\"knowledgeSourceName\":\"company_knowledge\"," + "\"expression\":\"MATCH (p:Person) WHERE p.status = :status RETURN p\","
                + "\"parameters\":{\"status\":\"ACTIVE\"},\"pageSize\":1}");

        String result = (String) query.invoke(arguments);

        assertFalse(result, result.startsWith("Error:"));
        assertEquals(1, executor.executions);
    }

    @Test
    public void shouldRequireExactAllowlistedSourceName() {
        KnowledgeGraphTools tools = new KnowledgeGraphTools(Collections.singletonList(source(new StubExecutor())));
        String result = tools.describeKnowledgeGraphTypes(
            "COMPANY_KNOWLEDGE",
            Collections.singletonList("Person"),
            Collections.<String>emptyList());
        assertTrue(result.startsWith("Error: UNKNOWN_KNOWLEDGE_SOURCE:"));
    }

    @Test
    public void shouldValidateSourceConfiguration() {
        try {
            KnowledgeGraphSource.builder("invalid", new StubExecutor(), schema()).build();
            fail("space must be required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("space"));
        }
    }

    /**
     * 按名称查找测试中的 Tool，便于断言动态 Tool 和注解扫描 Tool 组成同一列表。
     */
    private static Tool tool(List<Tool> tools, String name) {
        for (Tool tool : tools) {
            if (name.equals(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    /**
     * 按名称查找 Tool 参数。
     */
    private static Parameter parameter(Tool tool, String name) {
        for (Parameter parameter : tool.getParameters()) {
            if (name.equals(parameter.getName())) {
                return parameter;
            }
        }
        return null;
    }

    /**
     * 创建测试使用的固定知识源。
     */
    private static KnowledgeGraphSource source(GraphQueryExecutor executor) {
        return KnowledgeGraphSource.builder("company_knowledge", executor, schema())
            .description("People & companies")
            .space("company_space")
            .build();
    }

    /**
     * 构造同时包含可公开属性和测试用隐藏属性的最小业务 Schema。
     */
    private static GraphSchema schema() {
        GraphPropertyMetadata status = new GraphPropertyMetadata("Status", "Current status", null, Arrays.asList("ACTIVE", "INACTIVE"));
        return GraphSchema.builder()
            .metadata(new GraphSchemaMetadata("company", "1", "Company knowledge", "People and employers", null))
            .nodeType(
                GraphSchema.NodeType.of(
                    "Person",
                    new GraphElementMetadata("Person", "A known person"),
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true),
                    new GraphSchema.Property("status", GraphSchema.PropertyType.STRING, false, status)))
            .nodeType(
                GraphSchema.NodeType.of(
                    "Company",
                    new GraphElementMetadata("Company", "An employer"),
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true,
                        new GraphPropertyMetadata("Company name", "Legal name", null, null))))
            .edgeType(
                GraphSchema.EdgeType.of(
                    "WORKS_AT",
                    "Person",
                    "Company",
                    new GraphElementMetadata("Works at", "Employment relationship"),
                    new GraphSchema.Property("since", GraphSchema.PropertyType.DATE, false)))
            .edgeType(GraphSchema.EdgeType.of("KNOWS", "Person", "Person"))
            .build();
    }

    /**
     * 记录最终查询和执行选项的内存执行器，用于验证数据库调用边界。
     */
    private static final class StubExecutor implements GraphQueryExecutor {
        private TraversalQuery receivedQuery;
        private GraphOptions receivedOptions;
        private int executions;

        @Override
        public GraphResult execute(GraphQuery query, GraphOptions options) {
            executions++;
            receivedQuery = (TraversalQuery) query;
            receivedOptions = options;
            List<GraphRecord> records = new ArrayList<>();
            records.add(record("Alice", "Acme"));
            records.add(record("Bob", "Beta"));
            return new GraphResult(records, "backend query must not be returned", new GraphResultMetadata(records.size(), false, 7L));
        }

        @Override
        public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
            throw new AssertionError("native queries must never be used by knowledge graph tools");
        }

        /**
         * 构造包含额外 secret 属性的节点，验证结果格式化器只输出 Schema 允许的属性。
         */
        private GraphRecord record(String personName, String company) {
            GraphNode person = GraphNode.builder(personName.toLowerCase(), "Person")
                .property("name", personName)
                .property("status", "ACTIVE")
                .property("secret", "hidden-value")
                .build();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("p", person);
            values.put("company", company);
            return new GraphRecord(values);
        }
    }
}

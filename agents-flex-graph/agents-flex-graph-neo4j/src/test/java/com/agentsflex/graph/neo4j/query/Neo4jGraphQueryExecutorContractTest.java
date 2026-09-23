package com.agentsflex.graph.neo4j.query;

import com.agentsflex.graph.GraphException;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.query.GraphQuery;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultCursor;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.query.GraphQueryKind;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;

import org.junit.Test;
import org.neo4j.driver.QueryRunner;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.summary.Plan;
import org.neo4j.driver.summary.ResultSummary;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Neo4j 查询执行器的运行时契约，而不仅是 Cypher 文本编译结果。
 */
public class Neo4jGraphQueryExecutorContractTest {
    @Test
    public void shouldMaterializeScalarListAndNullValuesAndRespectMaxRecords() {
        Result result = result(Arrays.asList(
            record("name", Values.value("Alice"), "tags", Values.value(Arrays.<Object>asList("a", "b")),
                "missing", Values.NULL),
            record("name", Values.value("Bob"))));
        Neo4jGraphQueryExecutor executor = new Neo4jGraphQueryExecutor(runner(result), new Neo4jGraphStoreConfig());

        GraphResult graphResult = executor.execute(query(), GraphOptions.builder().maxRecords(1).build());

        assertEquals(1, graphResult.getRecords().size());
        GraphRecord row = graphResult.getRecords().get(0);
        assertEquals("Alice", row.get("name"));
        assertEquals(Arrays.asList("a", "b"), row.get("tags"));
        assertEquals(null, row.get("missing"));
        assertTrue(graphResult.getMetadata().isTruncated());
    }

    @Test
    public void cursorShouldStopAtMaxRecordsAndExposeTruncationMetadata() {
        Result result = result(Arrays.asList(record("id", Values.value("1")), record("id", Values.value("2"))));
        Neo4jGraphQueryExecutor executor = new Neo4jGraphQueryExecutor(runner(result), new Neo4jGraphStoreConfig());
        GraphResultCursor cursor = executor.executeCursor(query(), GraphOptions.builder().maxRecords(1).build());

        assertTrue(cursor.hasNext());
        assertEquals("1", cursor.next().get("id"));
        assertFalse(cursor.hasNext());
        assertEquals(1, cursor.getMetadata().getRecordCount());
        assertTrue(cursor.getMetadata().isTruncated());
        cursor.close();
        cursor.close();
        assertFalse(cursor.hasNext());
    }

    @Test
    public void runnerFailureContainingTimeoutShouldMapToQueryTimeout() {
        QueryRunner runner = (QueryRunner) Proxy.newProxyInstance(QueryRunner.class.getClassLoader(),
            new Class<?>[]{QueryRunner.class}, (proxy, method, args) -> {
                if ("run".equals(method.getName())) throw new RuntimeException("request timed out");
                return null;
            });
        Neo4jGraphQueryExecutor executor = new Neo4jGraphQueryExecutor(runner, new Neo4jGraphStoreConfig());
        try {
            executor.execute(query(), GraphOptions.DEFAULT);
        } catch (GraphException error) {
            assertEquals(GraphErrorCode.QUERY_TIMEOUT, error.getCode());
            return;
        }
        throw new AssertionError("timeout must be mapped to QUERY_TIMEOUT");
    }

    @Test
    public void readOnlyOptionsMustRejectNativeWriteBeforeRunnerExecution() {
        AtomicInteger executions = new AtomicInteger();
        QueryRunner runner = (QueryRunner) Proxy.newProxyInstance(QueryRunner.class.getClassLoader(),
            new Class<?>[]{QueryRunner.class}, (proxy, method, args) -> {
                if ("run".equals(method.getName())) executions.incrementAndGet();
                return result(Collections.<Record>emptyList());
            });
        Neo4jGraphQueryExecutor executor = new Neo4jGraphQueryExecutor(runner, new Neo4jGraphStoreConfig());
        try {
            executor.execute(NativeGraphQuery.of("CREATE (n)", Collections.<String, Object>emptyMap(), GraphQueryKind.WRITE),
                GraphOptions.builder().readOnly(true).build());
        } catch (GraphException error) {
            assertEquals(GraphErrorCode.INVALID_ARGUMENT, error.getCode());
            assertEquals(0, executions.get());
            return;
        }
        throw new AssertionError("read-only options must reject native writes");
    }

    @Test
    public void explainShouldExposeBackendAndPlanTreeDetails() {
        final Plan child = plan("NodeByLabelScan", Collections.<Plan>emptyList());
        final Plan root = plan("ProduceResults", Collections.singletonList(child));
        final ResultSummary summary = (ResultSummary) Proxy.newProxyInstance(ResultSummary.class.getClassLoader(),
            new Class<?>[]{ResultSummary.class}, (proxy, method, args) -> {
                if ("hasPlan".equals(method.getName())) return true;
                if ("plan".equals(method.getName())) return root;
                return null;
            });
        Result explainResult = (Result) Proxy.newProxyInstance(Result.class.getClassLoader(),
            new Class<?>[]{Result.class}, (proxy, method, args) -> {
                if ("consume".equals(method.getName())) return summary;
                return null;
            });
        Neo4jGraphQueryExecutor executor = new Neo4jGraphQueryExecutor(runner(explainResult), new Neo4jGraphStoreConfig());

        com.agentsflex.graph.query.GraphExplainResult explain = executor.explain(query(), GraphOptions.DEFAULT);

        assertEquals("neo4j", explain.getBackend());
        assertTrue(explain.getPlanText().contains("ProduceResults"));
        assertEquals("ProduceResults", explain.getDetails().get("operator"));
        assertEquals(1, ((List<?>) explain.getDetails().get("children")).size());
    }

    @Test
    public void driverBackedCursorShouldReleaseSessionOnClose() {
        final AtomicInteger sessionCloses = new AtomicInteger();
        Result result = result(Collections.singletonList(record("id", Values.value("1"))));
        Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
            new Class<?>[]{Session.class}, (proxy, method, args) -> {
                if ("run".equals(method.getName())) return result;
                if ("close".equals(method.getName())) sessionCloses.incrementAndGet();
                return null;
            });
        Driver driver = (Driver) Proxy.newProxyInstance(Driver.class.getClassLoader(),
            new Class<?>[]{Driver.class}, (proxy, method, args) -> {
                if ("session".equals(method.getName())) return session;
                return null;
            });
        Neo4jGraphQueryExecutor executor = new Neo4jGraphQueryExecutor(driver, new Neo4jGraphStoreConfig());
        GraphResultCursor cursor = executor.executeCursor(query(), GraphOptions.DEFAULT);
        cursor.close();
        cursor.close();
        assertEquals(1, sessionCloses.get());
    }

    private static Plan plan(final String operator, final List<Plan> children) {
        return (Plan) Proxy.newProxyInstance(Plan.class.getClassLoader(), new Class<?>[]{Plan.class},
            (proxy, method, args) -> {
                if ("operatorType".equals(method.getName())) return operator;
                if ("identifiers".equals(method.getName())) return Collections.singletonList("n");
                if ("arguments".equals(method.getName())) return Collections.emptyMap();
                if ("children".equals(method.getName())) return children;
                return null;
            });
    }

    private static TraversalQuery query() {
        return TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n")).build();
    }

    private static QueryRunner runner(final Result result) {
        return (QueryRunner) Proxy.newProxyInstance(QueryRunner.class.getClassLoader(),
            new Class<?>[]{QueryRunner.class}, (proxy, method, args) -> {
                if ("run".equals(method.getName())) return result;
                return null;
            });
    }

    private static Result result(final List<Record> records) {
        final Iterator<Record> iterator = records.iterator();
        return (Result) Proxy.newProxyInstance(Result.class.getClassLoader(),
            new Class<?>[]{Result.class}, (proxy, method, args) -> {
                if ("hasNext".equals(method.getName())) return iterator.hasNext();
                if ("next".equals(method.getName())) return iterator.next();
                if ("keys".equals(method.getName())) return records.isEmpty()
                    ? Collections.emptyList() : records.get(0).keys();
                return null;
            });
    }

    private static Record record(final Object... values) {
        final Map<String, Value> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) fields.put((String) values[i], (Value) values[i + 1]);
        return (Record) Proxy.newProxyInstance(Record.class.getClassLoader(),
            new Class<?>[]{Record.class}, (proxy, method, args) -> {
                if ("keys".equals(method.getName())) return new java.util.ArrayList<>(fields.keySet());
                if ("get".equals(method.getName())) {
                    if (args[0] instanceof String) return fields.get(args[0]);
                    return new java.util.ArrayList<>(fields.values()).get((Integer) args[0]);
                }
                if ("values".equals(method.getName())) return new java.util.ArrayList<>(fields.values());
                return null;
            });
    }
}

package com.agentsflex.graph.query;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.UnsupportedGraphFeatureException;
import com.agentsflex.graph.data.GraphNode;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证统一查询协议的分页、过滤树和结构化结果边界。
 *
 * <p>这些测试使用 API 层的最小 fake executor，重点保证后端适配器都必须遵守的
 * look-ahead、opaque cursor 和子图截断语义。</p>
 */
public class GraphQueryPagingContractTest {
    @Test
    public void everyFilterOperatorShouldPreserveItsOperandShape() {
        GraphFilter[] filters = new GraphFilter[]{
            GraphFilter.eq("n", "p", "x"),
            GraphFilter.ne("n", "p", "x"),
            GraphFilter.gt("n", "p", 1),
            GraphFilter.ge("n", "p", 1),
            GraphFilter.lt("n", "p", 2),
            GraphFilter.le("n", "p", 2),
            GraphFilter.in("n", "p", Arrays.asList("a", "b")),
            GraphFilter.notIn("n", "p", Arrays.asList("a", "b")),
            GraphFilter.between("n", "p", 1, 10),
            GraphFilter.isNull("n", "p"),
            GraphFilter.isNotNull("n", "p")
        };

        assertEquals(GraphFilter.Operator.EQ, filters[0].getOperator());
        assertEquals(GraphFilter.Operator.NOT_IN, filters[7].getOperator());
        assertEquals(GraphFilter.Operator.BETWEEN, filters[8].getOperator());
        assertEquals(2, ((java.util.List<?>) filters[8].getValue()).size());
        assertEquals(GraphFilter.Operator.IS_NULL, filters[9].getOperator());
        assertEquals(null, filters[9].getValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void betweenShouldRequireExactlyTwoValues() {
        GraphFilter.predicate("n", "p", GraphFilter.Operator.BETWEEN, Arrays.asList(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void membershipShouldRejectEmptyValues() {
        GraphFilter.in("n", "p", Collections.emptyList());
    }

    @Test
    public void defaultPagingShouldUseLookAheadAndPreserveSubgraphBoundaries() {
        final TraversalQuery[] received = new TraversalQuery[1];
        final GraphNode first = GraphNode.builder("1", "Person").build();
        final GraphNode second = GraphNode.builder("2", "Person").build();
        GraphQueryExecutor executor = new GraphQueryExecutor() {
            @Override
            public GraphResult execute(GraphQuery query, GraphOptions options) {
                received[0] = (TraversalQuery) query;
                GraphRecord firstRecord = new GraphRecord(Collections.<String, Object>singletonMap("n", first));
                GraphRecord secondRecord = new GraphRecord(Collections.<String, Object>singletonMap("n", second));
                return new GraphResult(Arrays.asList(firstRecord, secondRecord), "MATCH",
                    new GraphResultMetadata(2, false, 3L),
                    new GraphSubgraphResult(Arrays.asList(first, second), Collections.emptyList(), null));
            }

            @Override
            public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
                throw new AssertionError("native execution is not part of this test");
            }
        };

        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n")).build();
        GraphPageResult page = executor.executePage(query, GraphPageRequest.of(0, 1));

        assertEquals(2, received[0].getLimit());
        assertEquals(1, page.getResult().getRecords().size());
        assertTrue(page.hasNext());
        assertTrue(page.getNextCursor().startsWith("offset:1:"));
        assertNotNull(page.getResult().getSubgraph());
        assertEquals(1, page.getResult().getSubgraph().getNodes().size());
        assertEquals(1, page.getResult().getMetadata().getRecordCount());
        assertTrue(page.getResult().getMetadata().isTruncated());
    }

    @Test
    public void stringQueryConvenienceEntrypointsShouldReusePortableAst() {
        final TraversalQuery[] received = new TraversalQuery[1];
        GraphQueryExecutor executor = new GraphQueryExecutor() {
            @Override
            public GraphResult execute(GraphQuery query, GraphOptions options) {
                received[0] = (TraversalQuery) query;
                return new GraphResult(Collections.emptyList(), "MATCH");
            }

            @Override
            public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
                throw new AssertionError("native execution is not part of this test");
            }
        };

        executor.execute("MATCH (n:Person) WHERE n.age >= :age RETURN n",
            Collections.<String, Object>singletonMap("age", 18L));

        assertEquals("Person", received[0].getStart().getLabel());
        assertEquals(GraphFilter.Operator.GE, received[0].getFilter().getOperator());
        assertEquals(18L, received[0].getFilter().getValue());
    }

    @Test
    public void stringUnionAndOptionalEntrypointsShouldForwardCompositeAst() {
        final GraphQuery[] received = new GraphQuery[1];
        GraphQueryExecutor executor = new GraphQueryExecutor() {
            @Override
            public GraphResult execute(GraphQuery query, GraphOptions options) {
                received[0] = query;
                return new GraphResult(Collections.emptyList(), "MATCH");
            }

            @Override
            public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
                throw new AssertionError("native execution is not part of this test");
            }
        };

        executor.execute("MATCH (a:Person) RETURN a.name AS value UNION ALL "
            + "MATCH (b:Company) RETURN b.name AS value");
        assertTrue(received[0] instanceof GraphUnionQuery);
        executor.execute("OPTIONAL MATCH (a:Person) RETURN a");
        assertTrue(received[0] instanceof GraphOptionalQuery);
    }

    @Test
    public void exactPageShouldNotInventNextCursor() {
        GraphQueryExecutor executor = new StaticExecutor(new GraphResult(Arrays.asList(
            new GraphRecord(Collections.<String, Object>singletonMap("id", "1")),
            new GraphRecord(Collections.<String, Object>singletonMap("id", "2"))), "MATCH"));
        GraphPageResult page = executor.executePage(
            TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n")).build(),
            GraphPageRequest.of(0, 2));

        assertFalse(page.hasNext());
        assertEquals("", page.getNextCursor());
        assertFalse(page.getResult().getMetadata().isTruncated());
    }

    @Test(expected = UnsupportedGraphFeatureException.class)
    public void portablePagingShouldRejectOpaqueCursorWithoutAdapterOverride() {
        GraphQueryExecutor executor = new StaticExecutor(new GraphResult(Collections.emptyList(), "MATCH"));
        executor.executePage(TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n")).build(),
            GraphPageRequest.after("backend-token", 10));
    }

    /**
     * 新版默认游标必须绑定产生它的查询，防止 offset 被误用于另一组过滤条件。
     */
    @Test
    public void defaultCursorMustRejectReuseAcrossDifferentQueries() {
        GraphQueryExecutor executor = lookAheadExecutor();
        TraversalQuery firstQuery = queryForStatus("active");
        String cursor = executor.executePage(firstQuery, GraphPageRequest.of(0, 1)).getNextCursor();

        try {
            executor.executePage(queryForStatus("disabled"), GraphPageRequest.after(cursor, 1));
            fail("cursor from another query must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("different query"));
        }
    }

    /**
     * 分别构建但语义相同的查询应产生同一指纹，业务层无需复用同一个 Java 对象。
     */
    @Test
    public void equivalentQueryMustAcceptBoundCursor() {
        GraphQueryExecutor executor = lookAheadExecutor();
        String cursor = executor.executePage(queryForStatus("active"), GraphPageRequest.of(0, 1))
            .getNextCursor();

        GraphPageResult next = executor.executePage(queryForStatus("active"),
            GraphPageRequest.after(cursor, 1));

        assertTrue(next.getResult().getRecords().size() <= 1);
    }

    /**
     * Set 和 Map 的插入顺序不属于查询语义，不能导致等价查询的游标指纹不同。
     */
    @Test
    public void equivalentCollectionAndMapOrderMustAcceptBoundCursor() {
        GraphQueryExecutor executor = lookAheadExecutor();
        String cursor = executor.executePage(queryForStatuses(
                new HashSet<>(Arrays.asList("active", "pending")), map("a", 1, "b", 2)),
            GraphPageRequest.of(0, 1)).getNextCursor();

        GraphPageResult next = executor.executePage(queryForStatuses(
                new HashSet<>(Arrays.asList("pending", "active")), map("b", 2, "a", 1)),
            GraphPageRequest.after(cursor, 1));

        assertTrue(next.hasNext());
    }

    /**
     * 过滤值必须在创建时快照，外部集合变更不能改变已构建查询。
     */
    @Test
    public void filterCollectionValueMustBeImmutableSnapshot() {
        java.util.List<String> source = new java.util.ArrayList<>(Arrays.asList("active", "pending"));
        GraphFilter filter = GraphFilter.in("n", "status", source);
        source.clear();

        assertEquals(Arrays.asList("active", "pending"), filter.getValue());
        try {
            ((java.util.List<Object>) filter.getValue()).add("disabled");
            fail("filter value must be read-only");
        } catch (UnsupportedOperationException expected) {
            // 只读快照符合预期。
        }
    }

    @Test(expected = UnsupportedGraphFeatureException.class)
    public void portablePagingShouldRejectNonTraversalQuery() {
        GraphQueryExecutor executor = new StaticExecutor(new GraphResult(Collections.emptyList(), "MATCH"));
        executor.executePage(new GraphQuery() {
            @Override
            public void validate() {
            }
        }, GraphPageRequest.of(0, 10));
    }

    @Test(expected = IllegalArgumentException.class)
    public void pageRequestShouldRejectInvalidOffsetAndLimit() {
        GraphPageRequest.of(-1, 0);
    }

    @Test(expected = UnsupportedGraphFeatureException.class)
    public void defaultExplainShouldFailExplicitlyWhenBackendHasNoPlanSupport() {
        new StaticExecutor(new GraphResult(Collections.emptyList(), "MATCH"))
            .explain(TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n")).build(), GraphOptions.DEFAULT);
    }

    @Test
    public void resultCursorShouldBeReadOnlyAndClosedIdempotently() {
        GraphResult result = new GraphResult(Collections.singletonList(
            new GraphRecord(Collections.<String, Object>singletonMap("id", "1"))), "MATCH");
        GraphResultCursor cursor = GraphResultCursors.of(result);
        assertSame(result.getMetadata(), cursor.getMetadata());
        assertTrue(cursor.hasNext());
        cursor.next();
        assertFalse(cursor.hasNext());
        cursor.close();
        cursor.close();
        assertFalse(cursor.hasNext());
        try {
            cursor.next();
        } catch (java.util.NoSuchElementException expected) {
            return;
        }
        throw new AssertionError("closed cursor must reject next()");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void resultCursorShouldRejectRemove() {
        GraphResultCursor cursor = GraphResultCursors.of(new GraphResult(Collections.emptyList(), "MATCH"));
        cursor.remove();
    }

    private static final class StaticExecutor implements GraphQueryExecutor {
        private final GraphResult result;

        private StaticExecutor(GraphResult result) {
            this.result = result;
        }

        @Override
        public GraphResult execute(GraphQuery query, GraphOptions options) {
            return result;
        }

        @Override
        public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
            return result;
        }
    }

    /**
     * 构造始终返回两行的执行器，使 limit=1 时稳定生成下一页游标。
     */
    private static GraphQueryExecutor lookAheadExecutor() {
        return new StaticExecutor(new GraphResult(Arrays.asList(
            new GraphRecord(Collections.<String, Object>singletonMap("id", "1")),
            new GraphRecord(Collections.<String, Object>singletonMap("id", "2"))), "MATCH"));
    }

    /**
     * 构造仅过滤值不同的查询，用于验证游标和查询语义的绑定关系。
     */
    private static TraversalQuery queryForStatus(String status) {
        return TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Person"))
            .where(GraphFilter.eq("n", "status", status))
            .select(TraversalQuery.Projection.property("n", "name", "name"))
            .orderBy(new TraversalQuery.Sort("n", "name", TraversalQuery.SortDirection.ASC))
            .build();
    }

    /**
     * 构造同时含 Set 和 Map 比较值的查询，验证指纹的容器规范化。
     */
    private static TraversalQuery queryForStatuses(java.util.Set<String> statuses,
                                                   java.util.Map<String, Integer> attributes) {
        return TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Person"))
            .where(GraphFilter.and(GraphFilter.in("n", "status", statuses),
                GraphFilter.eq("n", "attributes", attributes)))
            .select(TraversalQuery.Projection.property("n", "name", "name"))
            .orderBy(new TraversalQuery.Sort("n", "name", TraversalQuery.SortDirection.ASC))
            .build();
    }

    /**
     * 创建可控插入顺序的 Map。
     */
    private static java.util.Map<String, Integer> map(String firstKey, int firstValue,
                                                      String secondKey, int secondValue) {
        java.util.Map<String, Integer> result = new LinkedHashMap<>();
        result.put(firstKey, firstValue);
        result.put(secondKey, secondValue);
        return result;
    }
}

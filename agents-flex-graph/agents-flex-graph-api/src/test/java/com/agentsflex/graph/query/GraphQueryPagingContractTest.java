package com.agentsflex.graph.query;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.UnsupportedGraphFeatureException;
import com.agentsflex.graph.data.GraphNode;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
        assertEquals("offset:1", page.getNextCursor());
        assertNotNull(page.getResult().getSubgraph());
        assertEquals(1, page.getResult().getSubgraph().getNodes().size());
        assertEquals(1, page.getResult().getMetadata().getRecordCount());
        assertTrue(page.getResult().getMetadata().isTruncated());
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

    @Test(expected = UnsupportedGraphFeatureException.class)
    public void portablePagingShouldRejectNonTraversalQuery() {
        GraphQueryExecutor executor = new StaticExecutor(new GraphResult(Collections.emptyList(), "MATCH"));
        executor.executePage(new GraphQuery() {
            @Override
            public void validate() {
            }
        }, GraphPageRequest.of(0, 10));
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
}

package com.agentsflex.graph.query;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 覆盖公共查询语言的异常、边界和不可变性契约。
 *
 * <p>这些测试不依赖具体数据库，重点验证输入被转换为统一 AST 时不会出现隐式放宽、
 * 可变引用泄漏或后端注入风险。</p>
 */
public class GraphQueryLanguageRobustnessTest {
    @Test
    public void shouldPreserveLiteralTypesAndEscapedStrings() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (n:Item {title: 'it\\'s', active: true, score: -1.5, missing: null}) "
                + "WHERE n.total >= -2 RETURN n").getQuery();

        Map<String, Object> properties = query.getStart().getProperties();
        assertEquals("it's", properties.get("title"));
        assertEquals(Boolean.TRUE, properties.get("active"));
        assertEquals(-1.5D, properties.get("score"));
        assertTrue(properties.containsKey("missing"));
        assertEquals(-2L, ((Number) query.getFilter().getValue()).longValue());
    }

    @Test
    public void shouldFreezeNestedParameterContainers() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("region", "CN");
        List<Object> values = new java.util.ArrayList<>();
        values.add(nested);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("values", values);

        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (n:Item) WHERE n.payload IN :values RETURN n", parameters);
        nested.put("mutated", Boolean.TRUE);
        values.clear();

        List<?> snapshot = (List<?>) parsed.getQuery().getFilter().getValue();
        assertEquals(1, snapshot.size());
        try {
            ((Map<?, ?>) snapshot.get(0)).clear();
            fail("nested map must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 预期行为。
        }
    }

    @Test
    public void semanticFailuresShouldUseStructuredParseError() {
        try {
            GraphQueryParser.parse("MATCH (n:Item {id: 1, id: 2}) RETURN n");
            fail("duplicate property keys must be rejected");
        } catch (GraphQueryParseException error) {
            assertEquals(GraphQueryErrorKind.SEMANTIC, error.getKind());
            assertTrue(error.getMessage().contains("duplicate"));
            assertTrue(error.getColumn() > 0);
        }

        try {
            GraphQueryParser.parse("MATCH (n:Item) WHERE n.id IN [] RETURN n");
            fail("empty membership values must be rejected");
        } catch (GraphQueryParseException error) {
            assertEquals(GraphQueryErrorKind.SEMANTIC, error.getKind());
        }
    }

    @Test
    public void shouldRejectUnsafeIdentifiersAndInvalidPatternRanges() {
        assertParseFailure("MATCH (n:Item$) RETURN n", "unexpected character");
        assertParseFailure("MATCH (n:Item)-[r:KNOWS*0..2]->(m) RETURN m", "hop range");
        assertParseFailure("MATCH (n:Item)-[r:KNOWS*3..2]->(m) RETURN m", "hop range");
        assertParseFailure("MATCH (n:Item)-[r:KNOWS {since: 2020}*1..2]->(m) RETURN m",
            "edge property patterns cannot use variable-length hops");
    }

    @Test
    public void unionParserShouldHandleStringsAndRejectMixedOperators() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (n:Item) WHERE n.name = 'UNION' RETURN n AS value UNION "
                + "MATCH (m:Other) RETURN m AS value");
        assertTrue(parsed.getGraphQuery() instanceof GraphUnionQuery);
        assertFalse(((GraphUnionQuery) parsed.getGraphQuery()).isAll());
        assertEquals("value", ((GraphUnionQuery) parsed.getGraphQuery()).getBranches().get(0)
            .getProjections().get(0).getOutputName());

        try {
            GraphQueryParser.parse("MATCH (n) RETURN n UNION ALL MATCH (m) RETURN m UNION MATCH (x) RETURN x");
            fail("mixed UNION operators must be rejected");
        } catch (GraphQueryParseException error) {
            assertTrue(error.getMessage().contains("cannot be mixed"));
        }
    }

    @Test
    public void groupedProjectionMustMatchGroupKey() {
        try {
            TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Item"))
                .select(TraversalQuery.Projection.property("n", "name", "name"),
                    TraversalQuery.Projection.count("n", "total"))
                .groupBy(new TraversalQuery.GroupKey("n", "city"))
                .build();
            fail("non-grouped property projection must be rejected");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("GROUP BY"));
        }
    }

    @Test
    public void templateShouldNotAcceptMissingOrUnexpectedCollectionShape() {
        GraphQueryTemplate template = GraphQueryParser.parseTemplate(
            "MATCH (n:Item) WHERE n.id IN :ids RETURN n");
        try {
            template.bind(Collections.<String, Object>singletonMap("ids", 1));
            fail("IN must receive a collection");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("collection"));
        }
    }

    private static void assertParseFailure(String expression, String message) {
        try {
            GraphQueryParser.parse(expression);
            fail("query should fail: " + expression);
        } catch (GraphQueryParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}

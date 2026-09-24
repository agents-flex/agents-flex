package com.agentsflex.graph.query;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 验证公共字符串查询语言到 {@link TraversalQuery} 的转换契约。
 *
 * <p>测试重点是语法和统一 AST，不依赖 Neo4j 或 Nebula，确保同一表达式可以交给任意后端编译器。</p>
 */
public class GraphQueryParserTest {
    @Test
    public void shouldParseLinearMatchWhereReturnOrderAndPagination() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("minAge", 18L);
        parameters.put("statuses", Arrays.asList("ACTIVE", "TRIAL"));
        String expression = "MATCH (p:Person)-[r:KNOWS]->(f:Person) "
            + "WHERE p.age >= :minAge AND f.status IN :statuses "
            + "RETURN p.name AS personName, f.name AS friendName "
            + "ORDER BY f.name DESC SKIP 5 LIMIT 20";

        ParsedGraphQuery parsed = GraphQueryParser.parse(expression, parameters);
        TraversalQuery query = parsed.getQuery();

        assertEquals("p", query.getStart().getAlias());
        assertEquals("Person", query.getStart().getLabel());
        assertEquals(1, query.getSteps().size());
        assertEquals("r", query.getSteps().get(0).getEdge().getAlias());
        assertEquals(TraversalQuery.Direction.OUT, query.getSteps().get(0).getEdge().getDirection());
        assertEquals("f", query.getSteps().get(0).getNode().getAlias());
        assertEquals(2, query.getProjections().size());
        assertEquals("personName", query.getProjections().get(0).getOutputName());
        assertEquals(TraversalQuery.SortDirection.DESC, query.getSorts().get(0).getDirection());
        assertEquals(5, query.getSkip());
        assertEquals(20, query.getLimit());
        assertEquals(Arrays.asList("ACTIVE", "TRIAL"), parsed.getParameters().get("statuses"));
    }

    @Test
    public void shouldParseIncomingAndVariableLengthAnonymousPatterns() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (person:Person)<-[:KNOWS*1..3]-(friend:Person) RETURN friend");
        TraversalQuery.Step step = parsed.getQuery().getSteps().get(0);

        assertEquals(TraversalQuery.Direction.IN, step.getEdge().getDirection());
        assertEquals(1, step.getEdge().getMinHops());
        assertEquals(3, step.getEdge().getMaxHops());
        assertTrue(step.getEdge().getAlias().startsWith("_e"));
        assertEquals("friend", parsed.getQuery().getProjections().get(0).getAlias());
    }

    @Test
    public void shouldParseBidirectionalAnyTypeEdge() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (a)-[link]-(b) RETURN DISTINCT b");
        TraversalQuery.EdgePattern edge = parsed.getQuery().getSteps().get(0).getEdge();

        assertEquals(TraversalQuery.Direction.BOTH, edge.getDirection());
        assertEquals(null, edge.getType());
        assertTrue(parsed.getQuery().isDistinct());
    }

    @Test
    public void shouldParseNestedBooleanFiltersAndNullPredicates() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (p:Person) WHERE NOT (p.deleted = false OR p.email IS NULL) RETURN p");
        GraphFilter filter = parsed.getQuery().getFilter();

        assertEquals(GraphFilter.Kind.NOT, filter.getKind());
        assertEquals(GraphFilter.Kind.OR, filter.getChildren().get(0).getKind());
        assertEquals(GraphFilter.Operator.IS_NULL,
            filter.getChildren().get(0).getChildren().get(1).getOperator());
    }

    @Test
    public void shouldAcceptCommonEqualityAndInequalitySpellings() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (p:Person) WHERE p.age == 18 OR p.name <> 'unknown' RETURN p");

        assertEquals(GraphFilter.Kind.OR, parsed.getQuery().getFilter().getKind());
        assertEquals(GraphFilter.Operator.EQ,
            parsed.getQuery().getFilter().getChildren().get(0).getOperator());
        assertEquals(GraphFilter.Operator.NE,
            parsed.getQuery().getFilter().getChildren().get(1).getOperator());
    }

    @Test
    public void shouldAllowQuotedPortableIdentifiersThatConflictWithKeywords() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (p:`Person`) WHERE p.`order` = 1 RETURN p.`order` AS `value`").getQuery();

        assertEquals("Person", query.getStart().getLabel());
        assertEquals("order", query.getFilter().getProperty());
        assertEquals("value", query.getProjections().get(0).getOutputName());
    }

    @Test
    public void shouldParseLiteralCollectionsBetweenAndCountDistinct() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (p:Person) WHERE p.age BETWEEN 18 AND 65 AND p.status IN ['A', 'B'] "
                + "RETURN COUNT(DISTINCT p.name) AS names");
        TraversalQuery query = parsed.getQuery();

        assertEquals(TraversalQuery.ProjectionKind.AGGREGATE, query.getProjections().get(0).getKind());
        assertEquals(TraversalQuery.AggregateFunction.COUNT_DISTINCT,
            query.getProjections().get(0).getAggregateFunction());
        assertEquals("name", query.getProjections().get(0).getProperty());
        assertEquals("names", query.getProjections().get(0).getOutputName());
    }

    @Test
    public void shouldPreservePropertyForNonDistinctCount() {
        TraversalQuery.Projection projection = GraphQueryParser.parse(
                "MATCH (p:Person) RETURN COUNT(p.name) AS names")
            .getQuery().getProjections().get(0);

        assertEquals(TraversalQuery.AggregateFunction.COUNT, projection.getAggregateFunction());
        assertEquals("name", projection.getProperty());
    }

    @Test
    public void shouldParsePortableAggregateFunctions() {
        TraversalQuery query = GraphQueryParser.parse(
                "MATCH (p:Person) RETURN SUM(p.score) AS total, "
                    + "AVG(p.score) AS average, MIN(p.score) AS minimum, MAX(p.score) AS maximum")
            .getQuery();

        assertEquals(TraversalQuery.AggregateFunction.SUM,
            query.getProjections().get(0).getAggregateFunction());
        assertEquals(TraversalQuery.AggregateFunction.AVG,
            query.getProjections().get(1).getAggregateFunction());
        assertEquals(TraversalQuery.AggregateFunction.MIN,
            query.getProjections().get(2).getAggregateFunction());
        assertEquals(TraversalQuery.AggregateFunction.MAX,
            query.getProjections().get(3).getAggregateFunction());
    }

    @Test
    public void shouldParseCountWildcardAsPortableNodeCount() {
        TraversalQuery.Projection projection = GraphQueryParser.parse(
            "MATCH (p:Person) RETURN COUNT(*) AS total").getQuery().getProjections().get(0);

        assertEquals(TraversalQuery.AggregateFunction.COUNT, projection.getAggregateFunction());
        assertEquals("p", projection.getAlias());
        assertEquals("total", projection.getOutputName());
    }

    @Test
    public void shouldParseCommonTextPredicates() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (p:Person) WHERE p.name CONTAINS 'an' "
                + "AND p.code STARTS WITH 'A' AND p.email ENDS WITH '.com' "
                + "AND p.alias REGEX '^a' RETURN p").getQuery();

        GraphFilter filter = query.getFilter();
        assertTrue(hasOperator(filter, GraphFilter.Operator.CONTAINS));
        assertTrue(hasOperator(filter, GraphFilter.Operator.STARTS_WITH));
        assertTrue(hasOperator(filter, GraphFilter.Operator.ENDS_WITH));
        assertTrue(hasOperator(filter, GraphFilter.Operator.REGEX));
    }

    private static boolean hasOperator(GraphFilter filter, GraphFilter.Operator operator) {
        if (filter.getKind() == GraphFilter.Kind.PREDICATE && filter.getOperator() == operator) return true;
        for (GraphFilter child : filter.getChildren()) {
            if (hasOperator(child, operator)) return true;
        }
        return false;
    }

    @Test
    public void parametersMustBeCopiedAndReadOnly() {
        java.util.List<String> statuses = new java.util.ArrayList<>(Arrays.asList("A", "B"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("statuses", statuses);
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (p:Person) WHERE p.status IN :statuses RETURN p", input);
        statuses.clear();
        input.clear();

        assertEquals(Arrays.asList("A", "B"), parsed.getParameters().get("statuses"));
        try {
            ((java.util.List<?>) parsed.getParameters().get("statuses")).clear();
            fail("parsed parameters must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 预期行为。
        }
    }

    @Test
    public void shouldRejectMissingParameterWithPosition() {
        try {
            GraphQueryParser.parse("MATCH (p:Person) WHERE p.age >= :age RETURN p");
            fail("missing parameter must be rejected");
        } catch (GraphQueryParseException error) {
            assertTrue(error.getMessage().contains("missing parameter 'age'"));
            assertTrue(error.getColumn() > 0);
            assertEquals(error.getExpression(), "MATCH (p:Person) WHERE p.age >= :age RETURN p");
        }
    }

    @Test
    public void shouldRejectMalformedQueryAndUnsafeIdentifiers() {
        assertParseFailure("MATCH (p:Person) RETURN", "expected projection alias");
        assertParseFailure("MATCH (p:Person) WHERE p.age >= 18", "expected RETURN clause");
        assertParseFailure("MATCH (p:Person) RETURN p RETURN p", "unexpected token after query");
        try {
            GraphQueryParser.parse("MATCH (p:Person) WHERE p.age = :missing RETURN p", Collections.emptyMap());
            fail("missing parameter must fail");
        } catch (GraphQueryParseException expected) {
            assertFalse(expected.getExpression().isEmpty());
        }
    }

    @Test
    public void shouldKeepCompilerIndependentOfInputDialect() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "match (p:Person) return p.name as name limit 3");
        assertEquals("Person", parsed.getQuery().getStart().getLabel());
        assertEquals(3, parsed.getQuery().getLimit());
    }

    @Test
    public void shouldParseMultipleLabelsAndEdgeTypes() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (p:Person:Employee)-[r:KNOWS|WORKS_WITH]->(f:Person) RETURN p").getQuery();
        assertEquals(Arrays.asList("Person", "Employee"), query.getStart().getLabels());
        assertEquals(Arrays.asList("KNOWS", "WORKS_WITH"), query.getSteps().get(0).getEdge().getTypes());
    }

    @Test
    public void shouldParsePathProjectionSyntax() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (a:Person)-[:KNOWS]->(b:Person) RETURN PATH AS route").getQuery();
        assertEquals(TraversalQuery.ProjectionKind.PATH, query.getProjections().get(0).getKind());
        assertEquals("route", query.getProjections().get(0).getOutputName());
    }

    @Test
    public void shouldParseUnionStringIntoPortableUnionAst() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "MATCH (p:Person) RETURN p.name AS name UNION ALL "
                + "MATCH (c:Company) RETURN c.name AS name");
        assertTrue(parsed.getGraphQuery() instanceof GraphUnionQuery);
        assertTrue(((GraphUnionQuery) parsed.getGraphQuery()).isAll());
        assertEquals(2, ((GraphUnionQuery) parsed.getGraphQuery()).getBranches().size());
    }

    @Test
    public void shouldParseGroupByAndHaving() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (p:Person) RETURN p.city AS city, COUNT(p) AS total "
                + "GROUP BY p.city HAVING p.total > 1").getQuery();
        assertEquals(1, query.getGroups().size());
        assertEquals("city", query.getGroups().get(0).getProperty());
        assertTrue(query.getHaving() != null);
    }

    @Test
    public void shouldParseOptionalMatchIntoPortableAst() {
        ParsedGraphQuery parsed = GraphQueryParser.parse(
            "OPTIONAL MATCH (p:Person)-[:KNOWS]->(f:Person) RETURN f");
        assertTrue(parsed.getGraphQuery() instanceof GraphOptionalQuery);
        assertEquals("f", ((GraphOptionalQuery) parsed.getGraphQuery()).getQuery()
            .getProjections().get(0).getAlias());
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

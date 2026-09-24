package com.agentsflex.graph.neo4j.query;

import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.GraphUnionQuery;
import com.agentsflex.graph.query.GraphOptionalQuery;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.GraphQueryParser;
import com.agentsflex.graph.query.TraversalQuery;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Neo4jCypherCompilerTest {
    @Test
    public void shouldParameterizeUserValues() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("person", "Person"))
            .where(GraphFilter.eq("person", "name", "Alice' OR true"))
            .build();

        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);
        assertTrue(compiled.statement.contains("$p0"));
        assertFalse(compiled.statement.contains("Alice"));
        assertTrue(compiled.parameters.containsValue("Alice' OR true"));
    }

    @Test
    public void shouldCompilePublicQueryStringToParameterizedCypher() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (p:Person)-[:KNOWS]->(f:Person) "
                + "WHERE p.age >= :age RETURN f.name AS name LIMIT 5",
            Collections.<String, Object>singletonMap("age", 18L)).getQuery();

        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);

        assertEquals("MATCH (p:Person)-[_e0:KNOWS]->(f:Person) "
            + "WHERE p.age >= $p0 RETURN f.name AS name LIMIT 5", compiled.statement);
        assertEquals(18L, compiled.parameters.get("p0"));
    }

    @Test
    public void shouldCompileFiniteVariableLengthPath() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("a"))
            .traverse(TraversalQuery.EdgePattern.edge("e", "KNOWS", TraversalQuery.Direction.OUT).hops(1, 3),
                TraversalQuery.NodePattern.anyNode("b"))
            .build();
        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);
        assertTrue(compiled.statement.contains("*1..3"));
        assertTrue(compiled.statement.contains("LIMIT 100"));
    }

    @Test
    public void shouldCompilePathProjectionDistinctSortAndPagination() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("person", "Person"))
            .traverse(TraversalQuery.EdgePattern.edge("knows", "KNOWS", TraversalQuery.Direction.OUT),
                TraversalQuery.NodePattern.node("friend", "Person"))
            .where(GraphFilter.and(
                GraphFilter.eq("person", "tenant", "acme"),
                GraphFilter.in("friend", "status", Arrays.asList("active", "pending"))))
            .select(TraversalQuery.Projection.path("path"),
                TraversalQuery.Projection.property("friend", "name", "friendName"))
            .orderBy(new TraversalQuery.Sort("friend", "name", TraversalQuery.SortDirection.DESC))
            .skip(10)
            .limit(5)
            .distinct(true)
            .build();

        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);
        assertEquals("MATCH p = (person:Person)-[knows:KNOWS]->(friend:Person) "
            + "WHERE (person.tenant = $p0 AND friend.status IN $p1) "
            + "RETURN DISTINCT p AS path, friend.name AS friendName "
            + "ORDER BY friend.name DESC SKIP 10 LIMIT 5", compiled.statement);
        assertEquals("acme", compiled.parameters.get("p0"));
        assertEquals(Arrays.asList("active", "pending"), compiled.parameters.get("p1"));
    }

    @Test
    public void shouldCompileEveryFilterOperatorWithParameters() {
        GraphFilter filter = GraphFilter.and(
            GraphFilter.ne("n", "a", 1),
            GraphFilter.gt("n", "b", 2),
            GraphFilter.ge("n", "c", 3),
            GraphFilter.lt("n", "d", 4),
            GraphFilter.le("n", "e", 5),
            GraphFilter.notIn("n", "f", Arrays.asList("x", "y")),
            GraphFilter.between("n", "g", 10, 20),
            GraphFilter.isNull("n", "h"),
            GraphFilter.isNotNull("n", "i"));
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n"))
            .where(filter).build();

        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);
        assertTrue(compiled.statement.contains("n.a <> $p0"));
        assertTrue(compiled.statement.contains("n.b > $p1"));
        assertTrue(compiled.statement.contains("n.c >= $p2"));
        assertTrue(compiled.statement.contains("n.d < $p3"));
        assertTrue(compiled.statement.contains("n.e <= $p4"));
        assertTrue(compiled.statement.contains("n.f NOT IN $p5"));
        assertTrue(compiled.statement.contains("n.g >= $p6 AND n.g <= $p7"));
        assertTrue(compiled.statement.contains("n.h IS NULL"));
        assertTrue(compiled.statement.contains("n.i IS NOT NULL"));
        assertEquals(8, compiled.parameters.size());
    }

    @Test
    public void shouldCompileCommonTextPredicatesWithParameters() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("person"))
            .where(GraphFilter.and(
                GraphFilter.contains("person", "name", "an"),
                GraphFilter.startsWith("person", "code", "A"),
                GraphFilter.endsWith("person", "email", ".com"),
                GraphFilter.regex("person", "alias", "^a")))
            .build();

        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);
        assertTrue(compiled.statement.contains("person.name CONTAINS $p0"));
        assertTrue(compiled.statement.contains("person.code STARTS WITH $p1"));
        assertTrue(compiled.statement.contains("person.email ENDS WITH $p2"));
        assertTrue(compiled.statement.contains("person.alias =~ $p3"));
    }

    @Test
    public void shouldCompileIncomingAndBidirectionalEdges() {
        TraversalQuery incoming = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("a"))
            .traverse(TraversalQuery.EdgePattern.edge("e", "KNOWS", TraversalQuery.Direction.IN),
                TraversalQuery.NodePattern.anyNode("b"))
            .build();
        TraversalQuery both = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("a"))
            .traverse(TraversalQuery.EdgePattern.edge("e", "KNOWS", TraversalQuery.Direction.BOTH),
                TraversalQuery.NodePattern.anyNode("b"))
            .build();

        assertTrue(new Neo4jCypherCompiler().compile(incoming).statement.contains("<-[e:KNOWS]-(b)"));
        assertTrue(new Neo4jCypherCompiler().compile(both).statement.contains("-[e:KNOWS]-(b)"));
    }

    @Test
    public void nativeQueryShouldPreserveStatementAndParameters() {
        NativeGraphQuery nativeQuery = NativeGraphQuery.of("MATCH (n) RETURN n", Collections.<String, Object>singletonMap("limit", 1));
        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(nativeQuery);
        assertEquals("MATCH (n) RETURN n", compiled.statement);
        assertEquals(1, compiled.parameters.get("limit"));
    }

    @Test
    public void shouldCompilePortableAggregateProjections() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("person", "Person"))
            .select(
                TraversalQuery.Projection.count("person", "total"),
                TraversalQuery.Projection.aggregate(TraversalQuery.AggregateFunction.COUNT_DISTINCT,
                    "person", "city", "cities"),
                TraversalQuery.Projection.aggregate(TraversalQuery.AggregateFunction.AVG,
                    "person", "age", "averageAge"))
            .build();

        assertEquals("MATCH (person:Person) RETURN count(person) AS total, "
                + "count(DISTINCT person.city) AS cities, avg(person.age) AS averageAge LIMIT 100",
            new Neo4jCypherCompiler().compile(query).statement);
    }

    @Test
    public void shouldCompileUnionBranchesWithIsolatedParameters() {
        TraversalQuery left = TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Person"))
            .where(GraphFilter.eq("n", "city", "Shanghai"))
            .select(TraversalQuery.Projection.property("n", "name", "name")).build();
        TraversalQuery right = TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Company"))
            .where(GraphFilter.eq("n", "city", "Beijing"))
            .select(TraversalQuery.Projection.property("n", "name", "name")).build();

        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler()
            .compile(GraphUnionQuery.unionAll(left, right));
        assertTrue(compiled.statement.contains("$u0_p0"));
        assertTrue(compiled.statement.contains(" UNION ALL "));
        assertTrue(compiled.statement.contains("$u1_p0"));
        assertEquals("Shanghai", compiled.parameters.get("u0_p0"));
        assertEquals("Beijing", compiled.parameters.get("u1_p0"));
    }

    @Test
    public void shouldCompileMultipleLabelsTypesAndPatternProperties() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (p:Person:Employee {status: :status})-[r:KNOWS|WORKS_WITH {since: 2020}]->(f:Person) "
                + "RETURN PATH AS route", Collections.<String, Object>singletonMap("status", "ACTIVE")).getQuery();
        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);
        assertTrue(compiled.statement.contains("(p:Person:Employee {status: $p0})"));
        assertTrue(compiled.statement.contains("[r:KNOWS|WORKS_WITH {since: $p1}]->"));
        assertTrue(compiled.statement.contains("p AS route"));
    }

    @Test
    public void shouldCompileGroupedAggregateWithoutDialectSyntaxInAst() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (p:Person) RETURN p.city AS city, COUNT(p) AS total GROUP BY p.city").getQuery();
        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);
        assertTrue(compiled.statement.contains("p.city AS city, count(p) AS total"));
    }

    @Test
    public void shouldCompileOptionalMatch() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("p", "Person"))
            .select(TraversalQuery.Projection.entity("p")).build();
        assertTrue(new Neo4jCypherCompiler().compile(GraphOptionalQuery.of(query)).statement
            .startsWith("OPTIONAL MATCH "));
    }

    @Test(expected = com.agentsflex.graph.UnsupportedGraphFeatureException.class)
    public void shouldRejectNeo4jGroupingPropertyThatIsNotProjected() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("p", "Person"))
            .select(TraversalQuery.Projection.count("p", "total"))
            .groupBy(new TraversalQuery.GroupKey("p", "city"))
            .build();
        new Neo4jCypherCompiler().compile(query);
    }

    @Test
    public void shouldPreserveEntityProjectionAliasAndParameterizePatternValues() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (n:Item {name: :name}) RETURN n AS item",
            Collections.<String, Object>singletonMap("name", "Alice")).getQuery();
        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile(query);
        assertTrue(compiled.statement.contains("RETURN n AS item"));
        assertEquals("Alice", compiled.parameters.get("p0"));
        assertFalse(compiled.statement.contains("Alice"));
    }

    @Test
    public void shouldCompileAllDirectionAndTypeCombinations() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (a:Person:Employee)-[r:KNOWS|WORKS_WITH]->(b:Person) RETURN b").getQuery();
        String statement = new Neo4jCypherCompiler().compile(query).statement;
        assertTrue(statement.contains("(a:Person:Employee)"));
        assertTrue(statement.contains("[r:KNOWS|WORKS_WITH]->"));
    }
}

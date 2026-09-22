package com.agentsflex.graph.neo4j.query;

import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.NativeGraphQuery;
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
}

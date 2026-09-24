package com.agentsflex.graph.nebula.query;

import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.GraphUnionQuery;
import com.agentsflex.graph.query.GraphOptionalQuery;
import com.agentsflex.graph.query.GraphQueryParser;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.UnsupportedGraphFeatureException;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Nebula nGQL 编译器的统一查询语义和参数绑定。
 */
public class NebulaNqlCompilerTest {
    @Test
    public void shouldCompileTraversalWithPathProjectionAndFilters() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("person", "Person"))
            .traverse(TraversalQuery.EdgePattern.edge("knows", "KNOWS", TraversalQuery.Direction.OUT),
                TraversalQuery.NodePattern.node("friend", "Person"))
            .where(GraphFilter.and(
                GraphFilter.eq("person", "tenant", "acme"),
                GraphFilter.in("friend", "status", Arrays.asList("active", "pending"))))
            .select(TraversalQuery.Projection.path("route"),
                TraversalQuery.Projection.property("friend", "name", "friendName"))
            .orderBy(new TraversalQuery.Sort("friend", "name", TraversalQuery.SortDirection.ASC))
            .skip(2)
            .limit(8)
            .build();

        NebulaNqlCompiler.Compiled compiled = new NebulaNqlCompiler().compile(query);
        assertEquals("MATCH p = (person:Person)-[knows:KNOWS]->(friend:Person) "
            + "WHERE (person.Person.tenant == $p0 AND friend.Person.status IN $p1) "
            + "RETURN p AS route, friend.Person.name AS friendName "
            + "ORDER BY friendName ASC SKIP 2 LIMIT 8", compiled.statement);
        assertEquals("acme", compiled.parameters.get("p0"));
        assertEquals(Arrays.asList("active", "pending"), compiled.parameters.get("p1"));
    }

    @Test
    public void shouldCompileIncomingBidirectionalAndVariableLengthEdges() {
        TraversalQuery incoming = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("a"))
            .traverse(TraversalQuery.EdgePattern.edge("e", "FOLLOWS", TraversalQuery.Direction.IN).hops(2, 4),
                TraversalQuery.NodePattern.anyNode("b"))
            .build();
        TraversalQuery both = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("a"))
            .traverse(TraversalQuery.EdgePattern.edge("e", "FOLLOWS", TraversalQuery.Direction.BOTH),
                TraversalQuery.NodePattern.anyNode("b"))
            .build();

        assertTrue(new NebulaNqlCompiler().compile(incoming).statement.contains("<-[e:FOLLOWS*2..4]-(b)"));
        assertTrue(new NebulaNqlCompiler().compile(both).statement.contains("-[e:FOLLOWS]-(b)"));
    }

    @Test
    public void shouldKeepEdgePropertiesUnqualifiedByNodeTag() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("a", "Person"))
            .traverse(TraversalQuery.EdgePattern.edge("e", "KNOWS", TraversalQuery.Direction.OUT),
                TraversalQuery.NodePattern.node("b", "Person"))
            .where(GraphFilter.gt("e", "weight", 1L))
            .select(TraversalQuery.Projection.property("e", "weight", "weight"))
            .build();

        assertEquals("MATCH (a:Person)-[e:KNOWS]->(b:Person) WHERE e.weight > $p0 "
                + "RETURN e.weight AS weight LIMIT 100",
            new NebulaNqlCompiler().compile(query).statement);
    }

    @Test
    public void nativeQueryShouldPreserveStatementAndParameters() {
        NativeGraphQuery query = NativeGraphQuery.of("FETCH PROP ON Person $id", Collections.<String, Object>singletonMap("id", "u1"));
        NebulaNqlCompiler.Compiled compiled = new NebulaNqlCompiler().compile(query);
        assertEquals(query.getStatement(), compiled.statement);
        assertEquals("u1", compiled.parameters.get("id"));
    }

    @Test
    public void shouldCompilePortableAggregateProjections() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("person", "Person"))
            .select(
                TraversalQuery.Projection.count("person", "total"),
                TraversalQuery.Projection.aggregate(TraversalQuery.AggregateFunction.COUNT_DISTINCT,
                    "person", "city", "cities"),
                TraversalQuery.Projection.aggregate(TraversalQuery.AggregateFunction.MAX,
                    "person", "score", "maxScore"))
            .build();

        assertEquals("MATCH (person:Person) RETURN count(person) AS total, "
                + "count(distinct person.Person.city) AS cities, max(person.Person.score) AS maxScore LIMIT 100",
            new NebulaNqlCompiler().compile(query).statement);
    }

    @Test
    public void shouldCompileUnionBranchesWithIsolatedParameters() {
        TraversalQuery left = TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Person"))
            .where(GraphFilter.eq("n", "status", "active"))
            .select(TraversalQuery.Projection.property("n", "name", "name")).build();
        TraversalQuery right = TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Company"))
            .where(GraphFilter.eq("n", "status", "active"))
            .select(TraversalQuery.Projection.property("n", "name", "name")).build();

        NebulaNqlCompiler.Compiled compiled = new NebulaNqlCompiler()
            .compile(GraphUnionQuery.union(left, right));
        assertTrue(compiled.statement.contains("$u0_p0"));
        assertTrue(compiled.statement.contains(" UNION "));
        assertTrue(compiled.statement.contains("$u1_p0"));
    }

    @Test
    public void shouldRejectGroupingUnsupportedByNebulaMatch() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("p", "Person",
                Collections.<String, Object>singletonMap("status", "ACTIVE")))
            .select(TraversalQuery.Projection.property("p", "city", "city"),
                TraversalQuery.Projection.count("p", "total"))
            .groupBy(new TraversalQuery.GroupKey("p", "city"))
            .build();
        try {
            new NebulaNqlCompiler().compile(query);
            org.junit.Assert.fail("Nebula MATCH GROUP BY must be rejected explicitly");
        } catch (UnsupportedGraphFeatureException expected) {
            assertTrue(expected.getMessage().contains("GROUP BY"));
        }
    }

    @Test
    public void shouldCompileOptionalMatch() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("p", "Person"))
            .select(TraversalQuery.Projection.entity("p")).build();
        assertTrue(new NebulaNqlCompiler().compile(GraphOptionalQuery.of(query)).statement
            .startsWith("OPTIONAL MATCH "));
    }

    @Test
    public void shouldCompileEntityProjectionAliasAndEdgePatternProperties() {
        TraversalQuery query = GraphQueryParser.parse(
            "MATCH (a:Person)-[r:KNOWS {since: :year}]->(b:Person) RETURN a AS source",
            Collections.<String, Object>singletonMap("year", 2020L)).getQuery();
        NebulaNqlCompiler.Compiled compiled = new NebulaNqlCompiler().compile(query);
        assertTrue(compiled.statement.contains("[r:KNOWS {since: $p0}]->"));
        assertTrue(compiled.statement.contains("RETURN a AS source"));
        assertEquals(2020L, compiled.parameters.get("p0"));
    }

    @Test(expected = com.agentsflex.graph.UnsupportedGraphFeatureException.class)
    public void shouldRejectPropertyProjectionFromUnlabeledNode() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n"))
            .select(TraversalQuery.Projection.property("n", "name", "name")).build();
        new NebulaNqlCompiler().compile(query);
    }
}

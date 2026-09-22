package com.agentsflex.graph.nebula.query;

import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
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
        assertEquals("MATCH path = (person:Person)-[knows:KNOWS]->(friend:Person) "
            + "WHERE (person.tenant == $p0 AND friend.status IN $p1) "
            + "RETURN path AS route, friend.name AS friendName "
            + "ORDER BY friend.name ASC SKIP 2 LIMIT 8", compiled.statement);
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
    public void nativeQueryShouldPreserveStatementAndParameters() {
        NativeGraphQuery query = NativeGraphQuery.of("FETCH PROP ON Person $id", Collections.<String, Object>singletonMap("id", "u1"));
        NebulaNqlCompiler.Compiled compiled = new NebulaNqlCompiler().compile(query);
        assertEquals(query.getStatement(), compiled.statement);
        assertEquals("u1", compiled.parameters.get("id"));
    }
}

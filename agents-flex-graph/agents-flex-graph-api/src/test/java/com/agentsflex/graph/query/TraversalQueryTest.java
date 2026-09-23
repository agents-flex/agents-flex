package com.agentsflex.graph.query;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖遍历查询 DSL 的组合语义和边界校验。
 */
public class TraversalQueryTest {
    @Test
    public void shouldBuildDefaultProjectionForSingleNodeAndPath() {
        TraversalQuery single = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n")).build();
        assertEquals(TraversalQuery.ProjectionKind.ENTITY, single.getProjections().get(0).getKind());
        assertEquals("n", single.getProjections().get(0).getAlias());
        assertFalse(single.hasVariableLengthStep());

        TraversalQuery path = TraversalQuery.from(TraversalQuery.NodePattern.node("a", "Person"))
            .traverse(TraversalQuery.EdgePattern.edge("e", "KNOWS", TraversalQuery.Direction.OUT),
                TraversalQuery.NodePattern.node("b", "Person"))
            .select(TraversalQuery.Projection.path("route"),
                TraversalQuery.Projection.property("b", "name", "friendName"))
            .orderBy(new TraversalQuery.Sort("b", "name", TraversalQuery.SortDirection.DESC))
            .skip(5)
            .limit(25)
            .distinct(true)
            .build();

        assertEquals("b", path.getSteps().get(0).getNode().getAlias());
        assertEquals(2, path.getProjections().size());
        assertEquals(1, path.getSorts().size());
        assertEquals(5, path.getSkip());
        assertEquals(25, path.getLimit());
        assertTrue(path.isDistinct());
    }

    @Test
    public void shouldValidateNestedFiltersAndVariableLength() {
        GraphFilter filter = GraphFilter.or(
            GraphFilter.and(GraphFilter.eq("person", "tenant", "acme"),
                GraphFilter.in("person", "status", Arrays.asList("active", "pending"))),
            GraphFilter.not(GraphFilter.isNull("friend", "name")));

        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("person", "Person"))
            .traverse(TraversalQuery.EdgePattern.edge("knows", "KNOWS", TraversalQuery.Direction.BOTH).hops(2, 4),
                TraversalQuery.NodePattern.node("friend", "Person"))
            .where(filter)
            .build();

        assertEquals(GraphFilter.Kind.OR, query.getFilter().getKind());
        assertEquals(2, query.getFilter().getChildren().size());
        assertTrue(query.hasVariableLengthStep());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectDuplicateNodeAndEdgeAliases() {
        TraversalQuery.from(TraversalQuery.NodePattern.anyNode("item"))
            .traverse(TraversalQuery.EdgePattern.edge("item", "RELATED", TraversalQuery.Direction.OUT),
                TraversalQuery.NodePattern.anyNode("other"))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectProjectionAliasThatIsNotInPattern() {
        TraversalQuery.from(TraversalQuery.NodePattern.anyNode("person"))
            .select(TraversalQuery.Projection.entity("missing"))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidPageLimit() {
        TraversalQuery.from(TraversalQuery.NodePattern.anyNode("person"))
            .limit(10_001)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidFilterCollectionShape() {
        GraphFilter.predicate("person", "age", GraphFilter.Operator.BETWEEN, Arrays.asList(18));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyCompositeFilter() {
        GraphFilter.and();
    }

    @Test
    public void edgePatternShouldPreserveDirectionAndHopRange() {
        TraversalQuery.EdgePattern edge = TraversalQuery.EdgePattern
            .edge("rel", "FOLLOWS", TraversalQuery.Direction.IN).hops(1, 6);
        assertEquals(TraversalQuery.Direction.IN, edge.getDirection());
        assertEquals(1, edge.getMinHops());
        assertEquals(6, edge.getMaxHops());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMixedAggregateAndEntityProjectionWithoutGrouping() {
        TraversalQuery.from(TraversalQuery.NodePattern.anyNode("person"))
            .select(TraversalQuery.Projection.entity("person"),
                TraversalQuery.Projection.count("person", "total"))
            .build();
    }

    @Test
    public void unionShouldRequireStableProjectionNames() {
        TraversalQuery people = TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Person"))
            .select(TraversalQuery.Projection.property("n", "name", "name")).build();
        TraversalQuery companies = TraversalQuery.from(TraversalQuery.NodePattern.node("n", "Company"))
            .select(TraversalQuery.Projection.property("n", "name", "name")).build();
        GraphUnionQuery query = GraphUnionQuery.unionAll(people, companies);
        assertTrue(query.isAll());
        assertEquals(2, query.getBranches().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unionShouldRejectDifferentProjectionCounts() {
        TraversalQuery left = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n"))
            .select(TraversalQuery.Projection.entity("n")).build();
        TraversalQuery right = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n"))
            .select(TraversalQuery.Projection.entity("n"), TraversalQuery.Projection.property("n", "name", "name"))
            .build();
        GraphUnionQuery.union(left, right);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unionShouldRejectDifferentProjectionSemantics() {
        TraversalQuery left = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n"))
            .select(TraversalQuery.Projection.property("n", "name", "value")).build();
        TraversalQuery right = TraversalQuery.from(TraversalQuery.NodePattern.anyNode("n"))
            .select(TraversalQuery.Projection.property("n", "age", "value")).build();
        GraphUnionQuery.unionAll(left, right);
    }
}

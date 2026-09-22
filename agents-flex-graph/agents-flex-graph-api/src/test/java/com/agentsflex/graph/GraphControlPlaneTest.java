package com.agentsflex.graph;

import com.agentsflex.graph.capability.GraphCapabilities;
import com.agentsflex.graph.connection.GraphConnectionRegistry;
import com.agentsflex.graph.capability.GraphFeature;
import com.agentsflex.graph.connection.GraphHealth;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaMigrationPlan;
import com.agentsflex.graph.schema.GraphSchemaMigrationPlanner;
import com.agentsflex.graph.transaction.GraphTransactionManager;
import com.agentsflex.graph.mutation.GraphWriter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 验证多连接注册、能力矩阵和迁移审批计划等控制面契约。
 */
public class GraphControlPlaneTest {
    @Test
    public void capabilityMatrixShouldIncludeUnsupportedFeaturesAndNotes() {
        GraphCapabilities capabilities = GraphCapabilities.of(GraphFeature.SCHEMA)
            .withNote(GraphFeature.SCHEMA, "additive only")
            .withNote(GraphFeature.TRANSACTIONS, "not exposed by client");

        assertTrue(capabilities.describe(GraphFeature.SCHEMA).isSupported());
        assertEquals("additive only", capabilities.describe(GraphFeature.SCHEMA).getNote());
        assertFalse(capabilities.describe(GraphFeature.TRANSACTIONS).isSupported());
        assertEquals("not exposed by client", capabilities.matrix().get(GraphFeature.TRANSACTIONS).getNote());
        assertEquals(GraphFeature.values().length, capabilities.matrix().size());
    }

    @Test
    public void registryShouldManageNamedConnectionsHealthAndLifecycle() {
        GraphConnectionRegistry registry = new GraphConnectionRegistry();
        StubStore first = new StubStore("neo4j");
        StubStore second = new StubStore("nebula");
        registry.register("prod-neo4j", first);
        registry.register("test-nebula", second);

        assertEquals(Arrays.asList("prod-neo4j", "test-nebula"), registry.names());
        assertSame(first, registry.require("prod-neo4j"));
        assertEquals(2, registry.health().size());
        assertTrue(registry.health().get("prod-neo4j").isUp());
        assertTrue(registry.remove("test-nebula"));
        assertTrue(second.closed);
        registry.close();
        assertTrue(first.closed);
    }

    @Test(expected = IllegalArgumentException.class)
    public void registryShouldRejectDuplicateConnectionName() {
        GraphConnectionRegistry registry = new GraphConnectionRegistry();
        registry.register("graph", new StubStore("one"));
        registry.register("graph", new StubStore("two"));
    }

    @Test
    public void migrationPlannerShouldOrderStepsAndEscalateRisk() {
        GraphSchema expected = GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person",
                new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true)))
            .build();
        GraphSchema actual = GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Legacy"))
            .build();

        GraphSchemaMigrationPlan plan = GraphSchemaMigrationPlanner.plan(expected, actual);
        assertEquals(GraphSchemaMigrationPlan.Risk.DESTRUCTIVE, plan.getRisk());
        assertTrue(plan.requiresApproval());
        assertEquals("ADD node:Person", plan.getSteps().get(0).getDescription());
        assertEquals("REMOVE node:Legacy", plan.getSteps().get(1).getDescription());
    }

    private static final class StubStore implements GraphStore {
        private final String backend;
        private boolean closed;

        private StubStore(String backend) {
            this.backend = backend;
        }

        @Override
        public GraphCapabilities capabilities() {
            return GraphCapabilities.none();
        }

        @Override
        public GraphManager manager() {
            return null;
        }

        @Override
        public GraphWriter writer() {
            return null;
        }

        @Override
        public GraphQueryExecutor query() {
            return null;
        }

        @Override
        public GraphTransactionManager transactions() {
            return null;
        }

        @Override
        public GraphHealth health() {
            return GraphHealth.up(backend, 1L);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

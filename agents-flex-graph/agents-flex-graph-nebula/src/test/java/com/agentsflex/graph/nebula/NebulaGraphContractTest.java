package com.agentsflex.graph.nebula;

import com.agentsflex.graph.UnsupportedGraphFeatureException;
import com.agentsflex.graph.capability.GraphFeature;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.nebula.manager.NebulaGraphManager;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaValidation;
import com.vesoft.nebula.Value;
import com.vesoft.nebula.client.graph.data.ValueWrapper;
import com.agentsflex.graph.nebula.query.NebulaGraphQueryExecutor;

import org.junit.Test;

import java.util.Collections;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Nebula 适配器在不连接真实集群时仍可保证的能力声明和 Schema 校验契约。
 */
public class NebulaGraphContractTest {
    @Test
    public void capabilitiesShouldDeclareMaterializedCursorAndTransactionLimits() {
        NebulaGraphStore store = new NebulaGraphStore(new NebulaGraphStoreConfig());
        try {
            assertTrue(store.capabilities().supports(GraphFeature.SCHEMA));
            assertFalse(store.capabilities().supports(GraphFeature.TRANSACTIONS));
            assertEquals("SessionPool returns a materialized ResultSet; executeCursor uses the portable in-memory fallback",
                store.capabilities().describe(GraphFeature.STREAMING_CURSOR).getNote());
            assertEquals(Collections.singletonList("ONLINE_BATCH"),
                store.capabilities().describe(GraphFeature.BULK_IMPORT).getModes());
        } finally {
            store.close();
        }
    }

    @Test(expected = UnsupportedGraphFeatureException.class)
    public void transactionsShouldFailExplicitlyInsteadOfSilentlyFallingBack() {
        NebulaGraphStore store = new NebulaGraphStore(new NebulaGraphStoreConfig());
        try {
            store.transactions();
        } finally {
            store.close();
        }
    }

    @Test
    public void schemaValidationShouldExposeUniqueIndexLimitationAndEdgeWarning() {
        NebulaGraphManager manager = new NebulaGraphManager(null, new NebulaGraphStoreConfig());
        GraphSchema schema = GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person"))
            .edgeType(GraphSchema.EdgeType.of("KNOWS", "Person", "Person"))
            .index(new GraphSchema.Index("person_name", GraphSchema.IndexTarget.NODE,
                "Person", Collections.singletonList("name"), true))
            .build();

        GraphSchemaValidation validation = manager.validateSchema("demo", schema);
        assertFalse(validation.isValid());
        assertTrue(validation.getErrors().get(0).contains("unique indexes"));
        assertTrue(validation.getWarnings().get(0).contains("endpoint labels"));
    }

    @Test
    public void configurationShouldValidatePoolAndSpaceBoundaries() {
        NebulaGraphStoreConfig config = new NebulaGraphStoreConfig()
            .setHost("localhost")
            .setPort(9669)
            .setDefaultSpace("tenant_graph")
            .setMinSessions(2)
            .setMaxSessions(4);
        assertEquals("tenant_graph", config.getDefaultSpace());
        assertEquals(2, config.getMinSessions());
        assertEquals(4, config.getMaxSessions());
    }

    @Test
    public void valueConversionShouldNormalizeNebulaScalarAndNullValues() throws Exception {
        NebulaGraphQueryExecutor executor = new NebulaGraphQueryExecutor(null, new NebulaGraphStoreConfig());
        Method convert = NebulaGraphQueryExecutor.class.getDeclaredMethod("convert", ValueWrapper.class);
        convert.setAccessible(true);

        assertEquals(42L, convert.invoke(executor, new ValueWrapper(Value.iVal(42L), "UTF-8")));
        assertEquals("Alice", convert.invoke(executor,
            new ValueWrapper(Value.sVal("Alice".getBytes("UTF-8")), "UTF-8")));
        assertEquals(null, convert.invoke(executor,
            new ValueWrapper(Value.nVal(com.vesoft.nebula.NullType.__NULL__), "UTF-8")));
    }
}

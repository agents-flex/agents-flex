package com.agentsflex.springboot.graph;

import com.agentsflex.graph.capability.GraphCapabilities;
import com.agentsflex.graph.connection.GraphConnectionRegistry;
import com.agentsflex.graph.connection.GraphHealth;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.GraphStore;
import com.agentsflex.graph.transaction.GraphTransactionManager;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.springboot.graph.nebula.NebulaGraphAutoConfiguration;
import com.agentsflex.springboot.graph.nebula.NebulaGraphProperties;
import com.agentsflex.springboot.graph.neo4j.Neo4jGraphAutoConfiguration;
import com.agentsflex.springboot.graph.neo4j.Neo4jGraphProperties;
import org.junit.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/** 验证 Graph Spring Boot Starter 的默认属性和自动配置开关。 */
public class GraphPropertiesTest {
    @Test
    public void neo4jPropertiesShouldBeDisabledByDefaultAndBindable() {
        Neo4jGraphProperties properties = new Neo4jGraphProperties();
        assertFalse(properties.isEnabled());
        assertEquals("bolt://localhost:7687", properties.getUri());
        assertEquals("neo4j", properties.getUsername());
        assertEquals("neo4j", properties.getDefaultSpace());

        properties.setEnabled(true);
        properties.setUri("bolt://graph.example:7687");
        properties.setUsername("app");
        properties.setPassword("secret");
        properties.setDefaultSpace("business");
        assertEquals("bolt://graph.example:7687", properties.getUri());
        assertEquals("app", properties.getUsername());
        assertEquals("secret", properties.getPassword());
        assertEquals("business", properties.getDefaultSpace());
    }

    @Test
    public void nebulaPropertiesShouldExposeExpectedDefaultsAndSetters() {
        NebulaGraphProperties properties = new NebulaGraphProperties();
        assertFalse(properties.isEnabled());
        assertEquals("127.0.0.1", properties.getHost());
        assertEquals(9669, properties.getPort());
        assertEquals("root", properties.getUsername());
        assertEquals("nebula", properties.getPassword());
        assertEquals("agents_flex", properties.getDefaultSpace());
        assertEquals(1, properties.getMinSessions());
        assertEquals(10, properties.getMaxSessions());

        properties.setEnabled(true);
        properties.setHost("nebula.example");
        properties.setPort(9779);
        properties.setUsername("app");
        properties.setPassword("secret");
        properties.setDefaultSpace("knowledge");
        properties.setMinSessions(2);
        properties.setMaxSessions(20);
        assertEquals("nebula.example", properties.getHost());
        assertEquals(9779, properties.getPort());
        assertEquals("app", properties.getUsername());
        assertEquals("secret", properties.getPassword());
        assertEquals("knowledge", properties.getDefaultSpace());
        assertEquals(2, properties.getMinSessions());
        assertEquals(20, properties.getMaxSessions());
    }

    @Test
    public void propertiesAndAutoConfigurationShouldDeclareStableSpringContracts() {
        ConfigurationProperties neo4j = Neo4jGraphProperties.class.getAnnotation(ConfigurationProperties.class);
        ConfigurationProperties nebula = NebulaGraphProperties.class.getAnnotation(ConfigurationProperties.class);
        assertNotNull(neo4j);
        assertNotNull(nebula);
        assertEquals("agents-flex.graph.neo4j", neo4j.prefix());
        assertEquals("agents-flex.graph.nebula", nebula.prefix());

        ConditionalOnProperty neo4jCondition = Neo4jGraphAutoConfiguration.class
            .getAnnotation(ConditionalOnProperty.class);
        ConditionalOnProperty nebulaCondition = NebulaGraphAutoConfiguration.class
            .getAnnotation(ConditionalOnProperty.class);
        assertEquals("agents-flex.graph.neo4j", neo4jCondition.prefix());
        assertEquals("agents-flex.graph.nebula", nebulaCondition.prefix());
        assertEquals("enabled", neo4jCondition.name()[0]);
        assertEquals("enabled", nebulaCondition.name()[0]);
        assertEquals("true", neo4jCondition.havingValue());
        assertEquals("true", nebulaCondition.havingValue());
    }

    @Test
    public void registryAutoConfigurationShouldIndexStoresWithoutTakingOwnership() {
        StubStore neo4j = new StubStore("neo4j");
        StubStore nebula = new StubStore("nebula");
        Map<String, GraphStore> stores = new LinkedHashMap<>();
        stores.put("neo4jGraphStore", neo4j);
        stores.put("nebulaGraphStore", nebula);

        GraphConnectionRegistry registry = new GraphRegistryAutoConfiguration()
            .graphConnectionRegistry(stores);
        assertEquals(2, registry.names().size());
        assertEquals(neo4j, registry.require("neo4jGraphStore"));
        registry.close();
        assertFalse(neo4j.closed);
        assertFalse(nebula.closed);
    }

    /** 仅用于验证注册表与 Spring 生命周期协作的最小存储替身。 */
    private static final class StubStore implements GraphStore {
        private final String backend;
        private boolean closed;

        private StubStore(String backend) { this.backend = backend; }
        @Override public GraphCapabilities capabilities() { return GraphCapabilities.none(); }
        @Override public GraphManager manager() { return null; }
        @Override public GraphWriter writer() { return null; }
        @Override public GraphQueryExecutor query() { return null; }
        @Override public GraphTransactionManager transactions() { return null; }
        @Override public GraphHealth health() { return GraphHealth.up(backend, 1L); }
        @Override public void close() { closed = true; }
    }
}

package com.agentsflex.graph.neo4j.manager;

import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import org.junit.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * 验证 Neo4j Schema DDL 能保留复合索引和唯一约束语义。
 */
public class Neo4jGraphManagerTest {
    @Test
    public void shouldCreateCompositeIndexAndUniqueConstraint() {
        List<String> statements = new ArrayList<>();
        Neo4jGraphManager manager = new Neo4jGraphManager(driver(statements), new Neo4jGraphStoreConfig());
        GraphSchema schema = GraphSchema.builder()
            .index(new GraphSchema.Index("person_lookup", GraphSchema.IndexTarget.NODE, "Person",
                Arrays.asList("tenant", "name"), false))
            .index(new GraphSchema.Index("person_external", GraphSchema.IndexTarget.NODE, "Person",
                Arrays.asList("tenant", "externalId"), true))
            .build();

        manager.applySchema("neo4j", schema, GraphManager.SchemaMode.ADDITIVE);

        assertTrue(statements.contains("CREATE INDEX person_lookup IF NOT EXISTS FOR (n:Person) "
            + "ON (n.tenant, n.name)"));
        assertTrue(statements.contains("CREATE CONSTRAINT person_external IF NOT EXISTS FOR (n:Person) "
            + "REQUIRE (n.tenant, n.externalId) IS UNIQUE"));
    }

    @Test
    public void validateOnlyShouldNotReportAppliedSteps() {
        Neo4jGraphManager manager = new Neo4jGraphManager(driver(new ArrayList<String>()), new Neo4jGraphStoreConfig());
        GraphSchema schema = GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person"))
            .edgeType(GraphSchema.EdgeType.any("KNOWS"))
            .build();
        assertEquals(0, manager.applySchemaResult("neo4j", schema,
            GraphManager.SchemaMode.VALIDATE_ONLY).getAppliedSteps().size());
    }

    private static Driver driver(List<String> statements) {
        Result result = (Result) Proxy.newProxyInstance(Result.class.getClassLoader(),
            new Class<?>[]{Result.class}, (proxy, method, args) -> null);
        Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
            new Class<?>[]{Session.class}, (proxy, method, args) -> {
                if ("run".equals(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof String) {
                    statements.add((String) args[0]);
                    return result;
                }
                return null;
            });
        return (Driver) Proxy.newProxyInstance(Driver.class.getClassLoader(), new Class<?>[]{Driver.class},
            (proxy, method, args) -> "session".equals(method.getName()) ? session : null);
    }
}

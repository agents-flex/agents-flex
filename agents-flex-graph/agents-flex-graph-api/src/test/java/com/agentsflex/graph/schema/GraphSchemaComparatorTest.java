package com.agentsflex.graph.schema;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Schema 差异比较可用于迁移预览和破坏性变更提示。
 */
public class GraphSchemaComparatorTest {
    @Test
    public void shouldReportAdditionsRemovalsAndDefinitionChanges() {
        GraphSchema expected = GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person",
                new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true),
                new GraphSchema.Property("age", GraphSchema.PropertyType.INT64, false)))
            .edgeType(GraphSchema.EdgeType.of("KNOWS", "Person", "Person"))
            .index(new GraphSchema.Index("person_name", GraphSchema.IndexTarget.NODE,
                "Person", Collections.singletonList("name"), true))
            .build();
        GraphSchema actual = GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person",
                new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
                new GraphSchema.Property("legacy", GraphSchema.PropertyType.STRING, false)))
            .edgeType(GraphSchema.EdgeType.of("KNOWS", "Person", "Company"))
            .nodeType(GraphSchema.NodeType.of("Company"))
            .index(new GraphSchema.Index("person_name", GraphSchema.IndexTarget.NODE,
                "Person", Collections.singletonList("name"), false))
            .build();

        GraphSchemaDiff diff = GraphSchemaComparator.compare(expected, actual);
        assertTrue(diff.getAdditions().contains("node:Person.property:age"));
        assertTrue(diff.getRemovals().contains("node:Person.property:legacy"));
        assertTrue(diff.getRemovals().contains("node:Company"));
        assertTrue(diff.getChanges().contains("node:Person.property:name definition changed"));
        assertTrue(diff.getChanges().contains("edge:KNOWS.endpoints Person->Person -> Person->Company"));
        assertTrue(diff.getChanges().contains("index:person_name definition changed"));
        assertTrue(diff.hasDestructiveChanges());
        assertFalse(diff.isEmpty());
    }

    @Test
    public void identicalSchemaShouldProduceEmptyDiffAndReadOnlyLists() {
        GraphSchema schema = GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person"))
            .edgeType(GraphSchema.EdgeType.of("KNOWS", "Person", "Person"))
            .build();
        GraphSchemaDiff diff = GraphSchemaComparator.compare(schema, schema);
        assertTrue(diff.isEmpty());
        assertFalse(diff.hasDestructiveChanges());
        try {
            diff.getAdditions().add("unexpected");
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("schema diff lists should be immutable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void comparatorShouldRejectNullSchema() {
        GraphSchemaComparator.compare(null, GraphSchema.builder().build());
    }
}

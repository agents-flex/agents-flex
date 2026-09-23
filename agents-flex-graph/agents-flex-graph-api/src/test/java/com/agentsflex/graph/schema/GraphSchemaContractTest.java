package com.agentsflex.graph.schema;

import com.agentsflex.graph.GraphException;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.UnsupportedGraphFeatureException;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.manager.GraphSchemaManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Schema 管理接口的默认兼容行为、模式传递和不可映射能力边界。
 */
public class GraphSchemaContractTest {
    @Test
    public void defaultApplyResultShouldNormalizeNullModeAndPreserveElapsedShape() {
        final GraphManager.SchemaMode[] received = new GraphManager.SchemaMode[1];
        GraphSchemaManager manager = new GraphSchemaManager() {
            @Override
            public void applySchema(String space, GraphSchema schema, GraphManager.SchemaMode mode) {
                received[0] = mode;
            }

            @Override
            public GraphSchemaValidation validateSchema(String space, GraphSchema schema) {
                return GraphSchemaValidation.valid();
            }
        };

        GraphSchemaApplyResult result = manager.applySchemaResult("demo", GraphSchema.builder().build(), null);
        assertTrue(result.isSuccess());
        assertEquals(GraphManager.SchemaMode.ADDITIVE, received[0]);
        assertEquals(Collections.emptyList(), result.getAppliedSteps());
        assertEquals(Collections.emptyList(), result.getWarnings());
        assertTrue(result.getExecutionTimeMillis() >= 0L);
    }

    @Test
    public void defaultApplyResultShouldMapGraphExceptionToStableErrorCode() {
        GraphSchemaManager manager = new GraphSchemaManager() {
            @Override
            public void applySchema(String space, GraphSchema schema, GraphManager.SchemaMode mode) {
                throw new GraphException(GraphErrorCode.SCHEMA_APPLY_FAILED, "denied");
            }

            @Override
            public GraphSchemaValidation validateSchema(String space, GraphSchema schema) {
                return GraphSchemaValidation.valid();
            }
        };

        GraphSchemaApplyResult result = manager.applySchemaResult("demo", GraphSchema.builder().build(),
            GraphManager.SchemaMode.VALIDATE_ONLY);
        assertFalse(result.isSuccess());
        assertEquals(GraphErrorCode.SCHEMA_APPLY_FAILED, result.getErrorCode());
        assertEquals("denied", result.getError());
        assertTrue(result.getAppliedSteps().isEmpty());
    }

    @Test
    public void contextOverloadsShouldDelegateToLegacyMethods() {
        final GraphManager.SchemaMode[] applied = new GraphManager.SchemaMode[1];
        final String[] validatedSpace = new String[1];
        GraphSchemaManager manager = new GraphSchemaManager() {
            @Override
            public void applySchema(String space, GraphSchema schema, GraphManager.SchemaMode mode) {
                applied[0] = mode;
            }

            @Override
            public GraphSchemaValidation validateSchema(String space, GraphSchema schema) {
                validatedSpace[0] = space;
                return GraphSchemaValidation.valid();
            }
        };
        GraphOptions options = GraphOptions.builder().space("tenant_a").build();
        manager.applySchema("demo", GraphSchema.builder().build(), GraphManager.SchemaMode.ADDITIVE, options);
        GraphSchemaValidation validation = manager.validateSchema("demo", GraphSchema.builder().build(), options);
        assertEquals(GraphManager.SchemaMode.ADDITIVE, applied[0]);
        assertEquals("demo", validatedSpace[0]);
        assertTrue(validation.isValid());
    }

    @Test(expected = UnsupportedGraphFeatureException.class)
    public void defaultInspectionShouldFailExplicitlyWhenBackendCannotIntrospect() {
        GraphSchemaManager manager = new GraphSchemaManager() {
            @Override public void applySchema(String space, GraphSchema schema, GraphManager.SchemaMode mode) { }
            @Override public GraphSchemaValidation validateSchema(String space, GraphSchema schema) {
                return GraphSchemaValidation.valid();
            }
        };
        manager.inspectSchema("demo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void schemaShouldRejectDuplicateNodeLabels() {
        GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Person"))
            .nodeType(GraphSchema.NodeType.of("Person"))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void schemaShouldRejectDuplicateIndexProperties() {
        GraphSchema.builder()
            .index(new GraphSchema.Index("person_name", GraphSchema.IndexTarget.NODE, "Person",
                Arrays.asList("name", "name"), false))
            .build();
    }
}

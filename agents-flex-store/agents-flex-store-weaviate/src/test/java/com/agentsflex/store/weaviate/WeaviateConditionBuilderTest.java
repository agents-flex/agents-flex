/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.weaviate;

import com.agentsflex.core.store.SearchWrapper;
import io.weaviate.client.v1.filters.Operator;
import io.weaviate.client.v1.filters.WhereFilter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WeaviateConditionBuilderTest {

    private final WeaviateConditionBuilder builder = new WeaviateConditionBuilder();

    @Test
    public void shouldConvertComparisonBetweenInAndNotIn() {
        WhereFilter filter = builder.build(new SearchWrapper()
            .eq("category", "AI")
            .ne("status", "deleted")
            .gt("views", 1)
            .ge("priority", 2)
            .lt("age", 60)
            .le("attempts", 3)
            .between("year", 2024, 2026)
            .in("tenant", Arrays.asList("a", "b"))
            .nin("kind", Arrays.asList("internal", "test"))
            .getCondition());

        assertEquals(Operator.And, filter.getOperator());
        assertEquals(9, filter.getOperands().length);
        assertEquals("metadata_category", filter.getOperands()[0].getPath()[0]);
        assertEquals(Operator.Equal, filter.getOperands()[0].getOperator());
        assertEquals("AI", filter.getOperands()[0].getValueText());
        assertEquals(Operator.Or, filter.getOperands()[7].getOperator());
        assertEquals(2, filter.getOperands()[7].getOperands().length);
        assertEquals(Operator.And, filter.getOperands()[8].getOperator());
    }

    @Test
    public void shouldPreserveNestedOrNotAndNullChecks() {
        WhereFilter filter = builder.build(new SearchWrapper()
            .isNull("optional")
            .orCriteria(group -> group.eq("category", "AI").ge("views", 10))
            .not(group -> group.eq("status", "deleted").lt("priority", 2))
            .getCondition());

        assertEquals(Operator.Or, filter.getOperator());
        assertEquals("IsNull", filter.getOperands()[0].getOperator());
        assertTrue(filter.toString(), containsOperator(filter, Operator.Not));
    }

    @Test
    public void shouldNormalizeCoreAndMetadataFields() {
        assertEquals("agentsFlexId", WeaviateConditionBuilder.normalizeField("id"));
        assertEquals("metadata_category", WeaviateConditionBuilder.normalizeField("category"));
        assertEquals("metadata_category", WeaviateConditionBuilder.normalizeField("metadata.category"));
        assertEquals("metadata_category", WeaviateConditionBuilder.normalizeField("metadataMap.category"));
    }

    @Test
    public void shouldRejectInvalidConditionsAndNames() {
        assertThrows(IllegalArgumentException.class,
            () -> builder.build(new SearchWrapper().in("category", new ArrayList<>()).getCondition()));
        assertThrows(IllegalArgumentException.class,
            () -> builder.build(new SearchWrapper().eq("category", new Object()).getCondition()));
        assertThrows(IllegalArgumentException.class, () -> WeaviateVectorStore.metadataProperty("nested.key"));
        assertThrows(IllegalArgumentException.class, () -> WeaviateVectorStore.validateCollectionName("lowercase"));
    }

    @Test
    public void configShouldDefensivelyCopyMetadataSchema() {
        WeaviateVectorStoreConfig config = new WeaviateVectorStoreConfig();
        Map<String, WeaviateMetadataType> types = new LinkedHashMap<>();
        types.put("optional", WeaviateMetadataType.TEXT);
        config.setMetadataFieldTypes(types);
        types.put("views", WeaviateMetadataType.INT);

        assertEquals(1, config.getMetadataFieldTypes().size());
        assertThrows(UnsupportedOperationException.class,
            () -> config.getMetadataFieldTypes().put("status", WeaviateMetadataType.TEXT));
        assertTrue(config.checkAvailable());

        config.setDefaultCollectionName("invalid-name");
        assertFalse(config.checkAvailable());
    }

    private boolean containsOperator(WhereFilter filter, String operator) {
        if (operator.equals(filter.getOperator())) {
            return true;
        }
        if (filter.getOperands() != null) {
            for (WhereFilter operand : filter.getOperands()) {
                if (containsOperator(operand, operator)) {
                    return true;
                }
            }
        }
        return false;
    }
}

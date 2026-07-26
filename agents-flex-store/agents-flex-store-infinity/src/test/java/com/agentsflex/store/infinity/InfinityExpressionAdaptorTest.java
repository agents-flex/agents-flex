/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.infinity;

import com.agentsflex.core.store.SearchWrapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class InfinityExpressionAdaptorTest {
    private final InfinityExpressionAdaptor adaptor = new InfinityExpressionAdaptor();

    @Test
    public void shouldRenderAllComparisonAndCollectionConditions() {
        String expression = new SearchWrapper()
            .eq("category", "AI")
            .ne("status", "deleted")
            .gt("views", 1)
            .ge("priority", 2)
            .lt("age", 60)
            .le("attempts", 3)
            .between("year", 2024, 2026)
            .in("tenant", Arrays.asList("a", "b"))
            .nin("kind", Arrays.asList("internal", "test"))
            .getCondition().toExpression(adaptor);

        assertEquals("metadata_category = 'AI' AND metadata_status != 'deleted'"
            + " AND metadata_views > 1 AND metadata_priority >= 2 AND metadata_age < 60"
            + " AND metadata_attempts <= 3 AND (metadata_year >= 2024 AND metadata_year <= 2026)"
            + " AND metadata_tenant IN ('a', 'b') AND metadata_kind NOT IN ('internal', 'test')", expression);
    }

    @Test
    public void shouldPreserveGroupsNotNullAndEscaping() {
        String expression = new SearchWrapper()
            .isNull("optional")
            .orCriteria(group -> group.eq("category", "O'Reilly").ge("views", 10))
            .not(group -> group.eq("status", "deleted"))
            .getCondition().toExpression(adaptor);

        assertEquals("metadata_optional = 'Null' OR (metadata_category = 'O''Reilly'"
            + " AND metadata_views >= 10) AND NOT (metadata_status = 'deleted')", expression);
    }

    @Test
    public void shouldNormalizeFieldsAndRejectUnsafeInput() {
        assertEquals("id", InfinityVectorStore.normalizeField("id"));
        assertEquals("metadata_category", InfinityVectorStore.normalizeField("category"));
        assertEquals("metadata_category", InfinityVectorStore.normalizeField("metadata.category"));
        assertEquals("metadata_category", InfinityVectorStore.normalizeField("metadataMap.category"));
        assertThrows(IllegalArgumentException.class,
            () -> InfinityVectorStore.normalizeField("category) OR true"));
        assertThrows(IllegalArgumentException.class,
            () -> new SearchWrapper().in("category", new ArrayList<>())
                .getCondition().toExpression(adaptor));
        assertThrows(IllegalArgumentException.class,
            () -> new SearchWrapper().eq("category", new Object())
                .getCondition().toExpression(adaptor));
    }

    @Test
    public void configShouldDefensivelyCopyMetadataSchema() {
        InfinityVectorStoreConfig config = new InfinityVectorStoreConfig();
        Map<String, InfinityMetadataType> schema = new LinkedHashMap<>();
        schema.put("optional", InfinityMetadataType.VARCHAR);
        config.setMetadataFieldTypes(schema);
        schema.put("views", InfinityMetadataType.INTEGER);

        assertEquals(1, config.getMetadataFieldTypes().size());
        assertThrows(UnsupportedOperationException.class,
            () -> config.getMetadataFieldTypes().put("status", InfinityMetadataType.VARCHAR));
        assertTrue(config.checkAvailable());
        config.setDefaultCollectionName("invalid-name");
        // checkAvailable 只校验连接和必要参数，严格标识符校验在 Store 构造时执行。
        assertTrue(config.checkAvailable());
        config.setVectorDimension(0);
        assertFalse(config.checkAvailable());
    }
}

/* Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com). */
package com.agentsflex.store.clickhouse;

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

public class ClickHouseExpressionAdaptorTest {
    @Test
    public void shouldRenderComparisonsWithTypedJsonExpressions() {
        ClickHouseExpressionAdaptor adaptor = new ClickHouseExpressionAdaptor();
        String sql = new SearchWrapper()
            .eq("category", "AI").ne("status", "deleted")
            .gt("views", 1).ge("priority", 2).lt("age", 60).le("attempts", 3)
            .between("year", 2024, 2026)
            .in("tenant", Arrays.asList("a", "b"))
            .nin("kind", Arrays.asList("internal", "test"))
            .getCondition().toExpression(adaptor);

        assertTrue(sql.contains("JSON_VALUE(`metadata`, '$.\\\"category\\\"') = ?"));
        assertTrue(sql.contains("toFloat64OrNull(JSON_VALUE(`metadata`, '$.\\\"views\\\"')) > ?"));
        assertTrue(sql.contains("BETWEEN ? AND ?"));
        assertTrue(sql.contains(" NOT IN (?, ?)"));
        assertEquals(Arrays.asList("AI", "deleted", 1, 2, 60, 3, 2024, 2026,
            "a", "b", "internal", "test"), adaptor.getParameters());
    }

    @Test
    public void shouldRenderNullBooleanNestedAndNotConditions() {
        ClickHouseExpressionAdaptor adaptor = new ClickHouseExpressionAdaptor();
        String sql = new SearchWrapper().eq("id", 42)
            .isNull("profile.name").isNotNull("profile.level")
            .eq("active", true)
            .not(group -> group.eq("status", "deleted"))
            .getCondition().toExpression(adaptor);

        assertTrue(sql.startsWith("`id` = ?"));
        assertTrue(sql.contains("JSON_VALUE(`metadata`, '$.\\\"profile\\\".\\\"name\\\"') IS NULL"));
        assertTrue(sql.contains("AND NOT (JSON_VALUE"));
        assertEquals(Arrays.asList("42", "true", "deleted"), adaptor.getParameters());
    }

    @Test
    public void shouldRejectUnsafeFieldsAndMixedCollections() {
        assertThrows(IllegalArgumentException.class, () -> new SearchWrapper()
            .eq("category') OR 1", "x").getCondition().toExpression(new ClickHouseExpressionAdaptor()));
        assertThrows(IllegalArgumentException.class, () -> new SearchWrapper()
            .in("category", new ArrayList<>()).getCondition().toExpression(new ClickHouseExpressionAdaptor()));
        assertThrows(IllegalArgumentException.class, () -> new SearchWrapper()
            .in("value", Arrays.asList(1, "2")).getCondition().toExpression(new ClickHouseExpressionAdaptor()));
    }

    @Test
    public void configShouldDefensivelyCopyProperties() {
        ClickHouseVectorStoreConfig config = new ClickHouseVectorStoreConfig();
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("compress", "1");
        config.setProperties(properties);
        properties.put("ssl", "true");
        assertEquals(1, config.getProperties().size());
        assertThrows(UnsupportedOperationException.class,
            () -> config.getProperties().put("x", "y"));
        assertTrue(config.checkAvailable());
        config.setQuantization("invalid");
        assertFalse(config.checkAvailable());
    }
}

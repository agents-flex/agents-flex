/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.pgvector;

import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.condition.Connector;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class PgvectorConditionBuilderTest {

    @Test
    public void shouldBuildParameterizedConditionsForAllOperators() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("metadataMap.status", "ready")
            .ne("deleted", true)
            .gt("score", 10)
            .ge("priority", 2)
            .lt("age", 60)
            .le("attempts", 3)
            .between("createdAt", 100, 200)
            .in("category", Arrays.asList("AI", "Java"))
            .nin("tenant", Arrays.asList("internal", "test"));
        PgvectorExpressionAdaptor builder = new PgvectorExpressionAdaptor();

        String sql = wrapper.toFilterExpression(builder);

        assertEquals("metadata #>> '{status}' = ? AND CAST(metadata #>> '{deleted}' AS boolean) <> ?"
                + " AND CAST(metadata #>> '{score}' AS numeric) > ?"
                + " AND CAST(metadata #>> '{priority}' AS numeric) >= ?"
                + " AND CAST(metadata #>> '{age}' AS numeric) < ?"
                + " AND CAST(metadata #>> '{attempts}' AS numeric) <= ?"
                + " AND CAST(metadata #>> '{createdAt}' AS numeric) BETWEEN ? AND ?"
                + " AND metadata #>> '{category}' IN (?, ?)"
                + " AND metadata #>> '{tenant}' NOT IN (?, ?)", sql);
        assertEquals(Arrays.asList("ready", true, 10, 2, 60, 3, 100, 200,
            "AI", "Java", "internal", "test"), builder.getParameters());
    }

    @Test
    public void shouldSupportColumnsNullsAndGroups() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("id", 42)
            .eq("optional", null)
            .orCriteria(group -> group
                .ge("views", 10)
                .in(Connector.AND_NOT, "category", Arrays.asList("hidden", "deleted")));
        PgvectorExpressionAdaptor builder = new PgvectorExpressionAdaptor();

        String sql = wrapper.toFilterExpression(builder);

        assertEquals("\"id\" = ? AND metadata #>> '{optional}' IS NULL"
            + " OR (CAST(metadata #>> '{views}' AS numeric) >= ?"
            + " AND NOT metadata #>> '{category}' IN (?, ?))", sql);
        assertEquals(Arrays.asList("42", 10, "hidden", "deleted"), builder.getParameters());
    }
}

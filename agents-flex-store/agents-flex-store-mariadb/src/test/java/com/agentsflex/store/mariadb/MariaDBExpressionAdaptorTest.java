/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.mariadb;

import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.condition.Connector;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class MariaDBExpressionAdaptorTest {

    @Test
    public void shouldBuildParameterizedConditionsForEveryOperator() {
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
        MariaDBExpressionAdaptor adaptor = new MariaDBExpressionAdaptor();

        String sql = wrapper.toFilterExpression(adaptor);

        assertEquals("JSON_VALUE(`metadata`, '$.\"status\"') = ?"
            + " AND JSON_VALUE(`metadata`, '$.\"deleted\"') <> ?"
            + " AND JSON_VALUE(`metadata`, '$.\"score\"') > ?"
            + " AND JSON_VALUE(`metadata`, '$.\"priority\"') >= ?"
            + " AND JSON_VALUE(`metadata`, '$.\"age\"') < ?"
            + " AND JSON_VALUE(`metadata`, '$.\"attempts\"') <= ?"
            + " AND JSON_VALUE(`metadata`, '$.\"createdAt\"') BETWEEN ? AND ?"
            + " AND JSON_VALUE(`metadata`, '$.\"category\"') IN (?, ?)"
            + " AND JSON_VALUE(`metadata`, '$.\"tenant\"') NOT IN (?, ?)", sql);
        assertEquals(Arrays.asList("ready", true, 10, 2, 60, 3, 100, 200,
            "AI", "Java", "internal", "test"), adaptor.getParameters());
    }

    @Test
    public void shouldSupportColumnsNestedMetadataNullsAndGroups() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("id", 42)
            .isNull("profile.level")
            .isNotNull("profile.name")
            .orCriteria(group -> group
                .ge("metadata.profile.level", 10)
                .in(Connector.AND_NOT, "category", Arrays.asList("hidden", "deleted")))
            .not(group -> group.eq("status", "archived"));
        MariaDBExpressionAdaptor adaptor = new MariaDBExpressionAdaptor();

        String sql = wrapper.toFilterExpression(adaptor);

        assertEquals("`id` = ? AND JSON_VALUE(`metadata`, '$.\"profile\".\"level\"') IS NULL"
            + " AND JSON_VALUE(`metadata`, '$.\"profile\".\"name\"') IS NOT NULL"
            + " OR (JSON_VALUE(`metadata`, '$.\"profile\".\"level\"') >= ?"
            + " AND NOT JSON_VALUE(`metadata`, '$.\"category\"') IN (?, ?))"
            + " AND NOT (JSON_VALUE(`metadata`, '$.\"status\"') = ?)", sql);
        assertEquals(Arrays.asList("42", 10, "hidden", "deleted", "archived"), adaptor.getParameters());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnsafeMetadataPath() {
        SearchWrapper wrapper = new SearchWrapper().eq("name') OR 1=1 --", "value");
        wrapper.toFilterExpression(new MariaDBExpressionAdaptor());
    }
}

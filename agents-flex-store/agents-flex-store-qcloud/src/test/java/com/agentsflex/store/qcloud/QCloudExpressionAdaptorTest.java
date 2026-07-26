/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.qcloud;

import com.agentsflex.core.store.SearchWrapper;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QCloudExpressionAdaptorTest {

    @Test
    public void shouldRenderTypedFilterAndBetween() {
        SearchWrapper wrapper = new SearchWrapper()
            .ge("age", 18)
            .eq("active", true)
            .in("name", Arrays.asList("alice", "bob"))
            .between("year", 2024, 2026);

        assertEquals(
            "age >= 18 and active = true and name in (\"alice\",\"bob\") "
                + "and (year >= 2024 and year <= 2026)",
            wrapper.toFilterExpression(QCloudExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldRenderSqlStyleNotInGroupAndEscaping() {
        SearchWrapper wrapper = new SearchWrapper().condition(
            "tenant = 'a' AND (status NOT IN ('deleted', 'blocked') OR author = 'a\\\"b')");

        assertEquals(
            "tenant = \"a\" and (status not in (\"deleted\",\"blocked\") or author = \"a\\\"b\")",
            wrapper.toFilterExpression(QCloudExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldSupportJsonPathAndRejectInvalidValues() {
        assertEquals(
            "profile.level = 3",
            new SearchWrapper().eq("profile.level", 3)
                .toFilterExpression(QCloudExpressionAdaptor.DEFAULT));
        assertInvalid(new SearchWrapper().eq("deletedAt", null), "NULL");
        assertInvalid(new SearchWrapper().eq("bad field", "value"), "field");
        assertInvalid(new SearchWrapper().in("status", Arrays.asList()), "at least one");
    }

    private void assertInvalid(SearchWrapper wrapper, String expectedMessage) {
        try {
            wrapper.toFilterExpression(QCloudExpressionAdaptor.DEFAULT);
            fail("Expected invalid Tencent VectorDB filter");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }
}

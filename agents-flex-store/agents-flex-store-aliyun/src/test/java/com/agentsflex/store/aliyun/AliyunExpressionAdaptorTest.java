/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.aliyun;

import com.agentsflex.core.store.SearchWrapper;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AliyunExpressionAdaptorTest {

    @Test
    public void shouldRenderDashVectorTypedFilter() {
        SearchWrapper wrapper = new SearchWrapper()
            .ge("age", 18)
            .eq("active", true)
            .in("name", Arrays.asList("alice", "bob"))
            .between("year", 2024, 2026);

        assertEquals(
            "age >= 18 and active = true and name in ('alice','bob') "
                + "and (year >= 2024 and year <= 2026)",
            wrapper.toFilterExpression(AliyunExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldRenderNotInAndEscapeString() {
        SearchWrapper wrapper = new SearchWrapper()
            .nin("status", Arrays.asList("deleted", "blocked"))
            .eq("author", "O'Reilly\\docs");

        assertEquals(
            "status not in ('deleted','blocked') and author = 'O\\'Reilly\\\\docs'",
            wrapper.toFilterExpression(AliyunExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldRejectNullInvalidFieldAndUnaryNot() {
        assertInvalid(new SearchWrapper().eq("deletedAt", null), "NULL");
        assertInvalid(new SearchWrapper().eq("bad field", "value"), "field");

        SearchWrapper not = new SearchWrapper()
            .condition("NOT (status = 'deleted')");
        assertInvalid(not, "unary NOT");
    }

    private void assertInvalid(SearchWrapper wrapper, String expectedMessage) {
        try {
            wrapper.toFilterExpression(AliyunExpressionAdaptor.DEFAULT);
            fail("Expected invalid DashVector filter");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }
}

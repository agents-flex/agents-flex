/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.milvus;

import com.agentsflex.core.store.SearchWrapper;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class MilvusExpressionAdaptorTest {

    @Test
    public void shouldRenderScalarValuesUsingTheirMilvusTypes() {
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.eq("category", "AI\"tools\\sdk")
            .ge("views", 20)
            .eq("published", true);

        assertEquals("category == \"AI\\\"tools\\\\sdk\" AND views >= 20 AND published == true",
            wrapper.toFilterExpression(MilvusExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldRenderInAndNotInAsArrayExpressions() {
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.in("category", Arrays.asList("AI", "ML"))
            .nin("views", Arrays.asList(10, 30));

        assertEquals("category in [\"AI\", \"ML\"] AND views not in [10, 30]",
            wrapper.toFilterExpression(MilvusExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldRenderBetweenAsTwoMilvusComparisons() {
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.between("views", 15, 25);

        assertEquals("(views >= 15 and views <= 25)",
            wrapper.toFilterExpression(MilvusExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldRenderNullChecks() {
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.isNull("title").isNotNull("content")
            .not(group -> group.eq("status", "deleted"));

        assertEquals("title is null AND content is not null AND NOT(status == \"deleted\")",
            wrapper.toFilterExpression(MilvusExpressionAdaptor.DEFAULT));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnsupportedValueTypes() {
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.eq("category", new Object());

        wrapper.toFilterExpression(MilvusExpressionAdaptor.DEFAULT);
    }
}

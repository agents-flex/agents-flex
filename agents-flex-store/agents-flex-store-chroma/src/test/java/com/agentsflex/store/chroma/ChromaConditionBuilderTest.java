/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.store.chroma;

import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.condition.Connector;
import com.google.gson.Gson;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ChromaConditionBuilderTest {

    private final ChromaConditionBuilder builder = new ChromaConditionBuilder();
    private final Gson gson = new Gson();

    @Test
    public void shouldBuildInNinAndBetweenFilters() {
        SearchWrapper wrapper = new SearchWrapper()
            .in("category", Arrays.asList("AI", "Java"))
            .nin("status", Arrays.asList("deleted"))
            .between("views", 5, 15);

        assertJsonEquals("{\"$and\":["
                + "{\"category\":{\"$in\":[\"AI\",\"Java\"]}},"
                + "{\"status\":{\"$nin\":[\"deleted\"]}},"
                + "{\"$and\":[{\"views\":{\"$gte\":5}},"
                + "{\"views\":{\"$lte\":15}}]}]}",
            builder.build(wrapper.getCondition()));
    }

    @Test
    public void shouldPreserveAndPrecedenceAndNegatedConnectors() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("a", 1)
            .eq(Connector.OR, "b", 2)
            .eq("c", 3)
            .in(Connector.AND_NOT, "status", Arrays.asList("hidden", "deleted"));

        assertJsonEquals("{\"$or\":["
                + "{\"a\":{\"$eq\":1}},"
                + "{\"$and\":["
                + "{\"b\":{\"$eq\":2}},"
                + "{\"c\":{\"$eq\":3}},"
                + "{\"status\":{\"$nin\":[\"hidden\",\"deleted\"]}}]}]}",
            builder.build(wrapper.getCondition()));
    }

    @Test
    public void shouldBuildNestedGroupsAndNormalizeMetadataPrefix() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("metadataMap.tenant", "one")
            .orCriteria(group -> group.gt("metadata.views", 10).lt("views", 20));

        assertJsonEquals("{\"$or\":["
                + "{\"tenant\":{\"$eq\":\"one\"}},"
                + "{\"$and\":["
                + "{\"views\":{\"$gt\":10}},"
                + "{\"views\":{\"$lt\":20}}]}]}",
            builder.build(wrapper.getCondition()));
    }

    private void assertJsonEquals(String expected, Map<String, Object> actual) {
        assertEquals(gson.fromJson(expected, Object.class), gson.fromJson(gson.toJson(actual), Object.class));
    }
}

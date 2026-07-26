/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.opensearch;

import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.condition.Connector;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class OpenSearchExpressionAdaptorTest {

    @Test
    public void shouldConvertAllConditionTypes() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("status", "ready")
            .ne("deleted", true)
            .gt("score", 10)
            .ge("priority", 2)
            .lt("age", 60)
            .le("attempts", 3)
            .between("createdAt", 100, 200)
            .in("category", Arrays.asList("AI", "Java"))
            .nin("tenant", Arrays.asList("internal", "test"));

        assertEquals("status:\"ready\" AND NOT deleted:true AND score:{10 TO *}"
                + " AND priority:[2 TO *] AND age:{* TO 60} AND attempts:[* TO 3]"
                + " AND createdAt:[100 TO 200] AND category:(\"AI\" OR \"Java\")"
                + " AND NOT tenant:(\"internal\" OR \"test\")",
            wrapper.toFilterExpression(OpenSearchExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldPreserveConnectorsGroupsAndNullChecks() {
        SearchWrapper wrapper = new SearchWrapper()
            .isNull("optional")
            .isNotNull("required")
            .orCriteria(group -> group
                .ge("views", 10)
                .in(Connector.AND_NOT, "category", Arrays.asList("hidden", "deleted")))
            .not(group -> group.eq("status", "deleted"));

        assertEquals("NOT _exists_:optional AND _exists_:required"
                + " OR (views:[10 TO *] AND NOT category:(\"hidden\" OR \"deleted\"))"
                + " AND NOT (status:\"deleted\")",
            wrapper.toFilterExpression(OpenSearchExpressionAdaptor.DEFAULT));
    }

    @Test
    public void shouldEscapeStringAndFieldCharacters() {
        SearchWrapper wrapper = new SearchWrapper().eq("metadataMap.type:name", "a\\b\"c");

        assertEquals("metadataMap.type\\:name:\"a\\\\b\\\"c\"",
            wrapper.toFilterExpression(OpenSearchExpressionAdaptor.DEFAULT));
    }
}

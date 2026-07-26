/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.mongodb.atlas;

import com.agentsflex.core.store.SearchWrapper;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MongoDBAtlasConditionBuilderTest {

    private final MongoDBAtlasConditionBuilder builder = new MongoDBAtlasConditionBuilder();

    @Test
    public void shouldConvertAllComparisonAndCollectionConditions() {
        SearchWrapper wrapper = new SearchWrapper()
            .eq("category", "AI")
            .ne("status", "deleted")
            .gt("views", 1)
            .ge("priority", 2)
            .lt("age", 60)
            .le("attempts", 3)
            .between("year", 2024, 2026)
            .in("tenant", Arrays.asList("a", "b"))
            .nin("kind", Arrays.asList("internal", "test"));

        BsonArray conditions = builder.build(wrapper.getCondition()).getArray("$and");

        assertEquals(9, conditions.size());
        assertEquals("AI", operation(conditions, 0, "metadataMap.category", "$eq").asString().getValue());
        assertEquals("deleted", operation(conditions, 1, "metadataMap.status", "$ne").asString().getValue());
        assertEquals(1, operation(conditions, 2, "metadataMap.views", "$gt").asInt32().getValue());
        assertEquals(2024, conditions.get(6).asDocument().getDocument("metadataMap.year")
            .getInt32("$gte").getValue());
        assertEquals(2, operation(conditions, 7, "metadataMap.tenant", "$in").asArray().size());
        assertEquals(2, operation(conditions, 8, "metadataMap.kind", "$nin").asArray().size());
    }

    @Test
    public void shouldPreserveGroupsNotAndNullChecks() {
        SearchWrapper wrapper = new SearchWrapper()
            .isNull("optional")
            .orCriteria(group -> group.eq("category", "AI").ge("views", 10))
            .not(group -> group.eq("status", "deleted").lt("priority", 2));

        BsonDocument filter = builder.build(wrapper.getCondition());

        assertTrue(filter.containsKey("$or"));
        String json = filter.toJson();
        assertTrue(json, json.contains("metadataMap.optional"));
        assertTrue(json, json.contains("$ne"));
        assertTrue(json, json.contains("$gte"));
        assertTrue(json, json.contains("$or"));
    }

    @Test
    public void shouldNormalizeCoreAndMetadataFields() {
        assertEquals("id", MongoDBAtlasConditionBuilder.normalizeField("id"));
        assertEquals("metadataMap.category", MongoDBAtlasConditionBuilder.normalizeField("category"));
        assertEquals("metadataMap.category", MongoDBAtlasConditionBuilder.normalizeField("metadata.category"));
        assertEquals("metadataMap.category", MongoDBAtlasConditionBuilder.normalizeField("metadataMap.category"));
    }

    @Test
    public void shouldRejectUnsupportedOrInvalidValues() {
        assertThrows(IllegalArgumentException.class,
            () -> builder.build(new SearchWrapper().in("category", new ArrayList<>()).getCondition()));
        assertThrows(IllegalArgumentException.class,
            () -> builder.build(new SearchWrapper().eq("category", new Object()).getCondition()));
        assertThrows(IllegalArgumentException.class,
            () -> MongoDBAtlasConditionBuilder.normalizeField(" "));
    }

    @Test
    public void configShouldDefensivelyCopyFilterFields() {
        MongoDBAtlasVectorStoreConfig config = new MongoDBAtlasVectorStoreConfig();
        List<String> fields = new ArrayList<>(Arrays.asList("category"));
        config.setFilterFields(fields);
        fields.add("views");

        assertEquals(Arrays.asList("category"), config.getFilterFields());
        assertThrows(UnsupportedOperationException.class, () -> config.getFilterFields().add("status"));
        assertTrue(config.checkAvailable());

        config.setDatabaseName(" ");
        assertFalse(config.checkAvailable());
    }

    private org.bson.BsonValue operation(
        BsonArray conditions,
        int index,
        String field,
        String operator
    ) {
        return conditions.get(index).asDocument().getDocument(field).get(operator);
    }
}

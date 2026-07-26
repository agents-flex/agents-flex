/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.infinity;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 使用真实 Infinity HTTP 服务、表和 HNSW 索引执行的集成测试。 */
public class InfinityVectorStoreIntegrationTest {
    private String serverUrl;
    private String database;
    private String firstCollection;
    private String secondCollection;
    private InfinityVectorStore store;

    @Before
    public void setUp() {
        Assume.assumeTrue("Enable with -Dagentsflex.infinity.integration=true",
            Boolean.getBoolean("agentsflex.infinity.integration"));
        serverUrl = System.getProperty("agentsflex.infinity.url", "http://127.0.0.1:23820");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        database = "af_it_" + suffix;
        firstCollection = "docs_a";
        secondCollection = "docs_b";

        InfinityVectorStoreConfig config = baseConfig(firstCollection);
        Map<String, InfinityMetadataType> schema = new LinkedHashMap<>();
        schema.put("optional", InfinityMetadataType.VARCHAR);
        config.setMetadataFieldTypes(schema);
        store = new InfinityVectorStore(config);
    }

    @After
    public void tearDown() {
        if (store != null) store.close();
        if (database != null) {
            JSONObject body = new JSONObject();
            body.put("drop_option", "ignore_if_not_exists");
            AgentsFlexHttpClient.getDefault().delete(serverUrl + "/databases/" + database,
                Collections.singletonMap("Content-Type", "application/json"), body.toJSONString());
        }
    }

    @Test
    public void shouldKeepCollectionsIsolatedAndUseRealHnswIndex() {
        StoreOptions first = StoreOptions.ofCollectionName(firstCollection);
        StoreOptions second = StoreOptions.ofCollectionName(secondCollection);
        assertTrue(store.doStore(Collections.singletonList(
            document("same-id", "first", "AI", "ready", 10, new float[]{1, 0, 0})), first).isSuccess());
        assertTrue(store.doStore(Collections.singletonList(
            document("same-id", "second", "Java", "ready", 20, new float[]{1, 0, 0})), second).isSuccess());

        assertEquals("first", store.doSearch(vectorSearch(), first).get(0).getContent());
        assertEquals("second", store.doSearch(vectorSearch(), second).get(0).getContent());
        String indexes = AgentsFlexHttpClient.getDefault().get(serverUrl + "/databases/" + database
            + "/tables/" + firstCollection + "/indexes");
        assertTrue(indexes, indexes.contains("embedding_hnsw"));
    }

    @Test
    public void shouldApplyComplexAndSqlFiltersOnServer() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        Document ops = document("doc-3", "Ops document", "Ops", "deleted", 30, new float[]{0, 1, 0});
        ops.putMetadata("optional", "set");
        assertTrue(store.doStore(Arrays.asList(
            document("doc-1", "AI document", "AI", "ready", 10, new float[]{1, 0, 0}),
            document("doc-2", "Java document", "Java", "ready", 20, new float[]{0.9f, 0.1f, 0}),
            ops
        ), options).isSuccess());

        List<Document> result = store.doSearch(vectorSearch()
            .in("category", Arrays.asList("AI", "Ops"))
            .between("views", 5, 15)
            .isNull("optional")
            .not(group -> group.eq("status", "deleted"))
            .outputFields("category", "views", "optional")
            .outputVector(true)
            .minScore(0.5), options);
        assertEquals(1, result.size());
        assertEquals("doc-1", result.get(0).getId());
        assertEquals("AI", result.get(0).getMetadata("category"));
        assertNull(result.get(0).getMetadata("optional"));
        assertArrayEquals(new float[]{1, 0, 0}, result.get(0).getVector(), 0.0001f);
        assertNotNull(result.get(0).getScore());

        List<Document> filterOnly = store.doSearch(new SearchWrapper().withVector(false)
            .condition("views >= 20 AND category NOT IN ('AI')")
            .outputFields("category", "views").maxResults(10), options);
        assertEquals(2, filterOnly.size());
        assertNull(filterOnly.get(0).getScore());
        assertNull(filterOnly.get(0).getVector());

        List<Document> nested = store.doSearch(new SearchWrapper().withVector(false)
            .condition("(category = 'AI' OR category = 'Ops') AND NOT (status = 'deleted')")
            .maxResults(10), options);
        assertEquals(1, nested.size());
        assertEquals("doc-1", nested.get(0).getId());

        List<Document> notNull = store.doSearch(new SearchWrapper().withVector(false)
            .isNotNull("optional").maxResults(10), options);
        assertEquals(1, notNull.size());
        assertEquals("doc-3", notNull.get(0).getId());

        Document quoted = document("doc-4", "quoted", "O'Reilly", "ready", 40, new float[]{1, 0, 0});
        assertTrue(store.doStore(Collections.singletonList(quoted), options).isSuccess());
        assertEquals("doc-4", store.doSearch(new SearchWrapper().withVector(false)
            .eq("category", "O'Reilly").maxResults(10), options).get(0).getId());
    }

    @Test
    public void shouldUpsertWithoutDuplicateRowsAndAddMetadataColumns() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        Document document = document("doc-1", "before", "AI", "ready", 10, new float[]{1, 0, 0});
        assertTrue(store.doStore(Collections.singletonList(document), options).isSuccess());

        document.setContent("after");
        document.putMetadata("rank", 99L);
        assertTrue(store.doUpdate(Collections.singletonList(document), options).isSuccess());
        List<Document> updated = store.doSearch(new SearchWrapper().withVector(false)
            .eq("id", "doc-1").outputFields("rank").maxResults(10), options);
        assertEquals(1, updated.size());
        assertEquals("after", updated.get(0).getContent());
        assertEquals(99L, ((Number) updated.get(0).getMetadata("rank")).longValue());

        StoreResult deleted = store.doDelete(Collections.singletonList("doc-1"), options);
        assertTrue(deleted.toString(), deleted.isSuccess());
        assertTrue(store.doSearch(new SearchWrapper().withVector(false).maxResults(10), options).isEmpty());
    }

    @Test
    public void shouldRejectMissingCollectionWhenAutomaticCreationIsDisabled() {
        InfinityVectorStoreConfig config = baseConfig(secondCollection);
        config.setAutoCreateCollection(false);
        try (InfinityVectorStore noAutoCreate = new InfinityVectorStore(config)) {
            StoreResult result = noAutoCreate.doStore(Collections.singletonList(
                document("doc", "content", "AI", "ready", 1, new float[]{1, 0, 0})), StoreOptions.DEFAULT);
            assertFalse(result.isSuccess());
        }
    }

    @Test
    public void shouldSearchWithL2AndMapDistanceToScore() {
        InfinityVectorStoreConfig config = baseConfig(secondCollection);
        config.setSimilarity(InfinitySimilarity.L2);
        try (InfinityVectorStore l2Store = new InfinityVectorStore(config)) {
            StoreOptions options = StoreOptions.ofCollectionName(secondCollection);
            assertTrue(l2Store.doStore(Arrays.asList(
                document("near", "near", "AI", "ready", 1, new float[]{1, 0, 0}),
                document("far", "far", "AI", "ready", 2, new float[]{0, 1, 0})
            ), options).isSuccess());
            List<Document> result = l2Store.doSearch(vectorSearch().minScore(0.75), options);
            assertEquals(1, result.size());
            assertEquals("near", result.get(0).getId());
            assertEquals(1.0f, result.get(0).getScore(), 0.0001f);
        }
    }

    @Test
    public void shouldSearchWithInnerProduct() {
        InfinityVectorStoreConfig config = baseConfig("docs_ip");
        config.setSimilarity(InfinitySimilarity.IP);
        try (InfinityVectorStore ipStore = new InfinityVectorStore(config)) {
            StoreOptions options = StoreOptions.ofCollectionName("docs_ip");
            assertTrue(ipStore.doStore(Arrays.asList(
                document("best", "best", "AI", "ready", 1, new float[]{1, 0, 0}),
                document("other", "other", "AI", "ready", 2, new float[]{0, 1, 0})
            ), options).isSuccess());
            List<Document> result = ipStore.doSearch(vectorSearch(), options);
            assertEquals("best", result.get(0).getId());
            assertEquals(1.0f, result.get(0).getScore(), 0.0001f);
        }
    }

    private InfinityVectorStoreConfig baseConfig(String collection) {
        InfinityVectorStoreConfig config = new InfinityVectorStoreConfig();
        config.setServerUrl(serverUrl);
        config.setDatabaseName(database);
        config.setDefaultCollectionName(collection);
        config.setVectorDimension(3);
        return config;
    }

    private SearchWrapper vectorSearch() {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(new float[]{1, 0, 0});
        return wrapper;
    }

    private Document document(String id, String content, String category, String status, int views, float[] vector) {
        Document document = Document.of(content);
        document.setId(id);
        document.setTitle("title-" + id);
        document.setVector(vector);
        document.putMetadata("category", category);
        document.putMetadata("status", status);
        document.putMetadata("views", views);
        return document;
    }
}

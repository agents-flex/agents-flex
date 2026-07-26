/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.mongodb.atlas;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 真实 MongoDB Atlas Local 集成测试。
 *
 * <p>测试会创建真实 Vector Search Index 并执行 {@code $vectorSearch}，不能由普通
 * MongoDB Community Server 替代。通过系统属性显式开启，测试结束后删除随机数据库。</p>
 */
public class MongoDBAtlasVectorStoreIntegrationTest {

    private MongoClient cleanupClient;
    private MongoDBAtlasVectorStore store;
    private String databaseName;
    private String firstCollection;
    private String secondCollection;

    @Before
    public void setUp() {
        Assume.assumeTrue("Enable with -Dagentsflex.mongodb-atlas.integration=true",
            Boolean.getBoolean("agentsflex.mongodb-atlas.integration"));

        String uri = System.getProperty("agentsflex.mongodb-atlas.uri",
            "mongodb://127.0.0.1:27018/?directConnection=true");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        databaseName = "agentsflex_atlas_it_" + suffix;
        firstCollection = "documents_a";
        secondCollection = "documents_b";

        MongoDBAtlasVectorStoreConfig config = new MongoDBAtlasVectorStoreConfig();
        config.setConnectionString(uri);
        config.setDatabaseName(databaseName);
        config.setDefaultCollectionName(firstCollection);
        config.setVectorDimension(3);
        config.setIndexReadyTimeoutMillis(180_000L);
        config.setFilterFields(Arrays.asList("category", "status", "views", "optional"));
        store = new MongoDBAtlasVectorStore(config);
        cleanupClient = MongoClients.create(uri);
    }

    @After
    public void tearDown() {
        if (cleanupClient != null && databaseName != null) {
            cleanupClient.getDatabase(databaseName).drop();
            cleanupClient.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    public void shouldKeepCollectionsIsolatedAndSearchRealAtlasIndex() {
        StoreOptions first = StoreOptions.ofCollectionName(firstCollection);
        StoreOptions second = StoreOptions.ofCollectionName(secondCollection);
        assertTrue(store.doStore(Collections.singletonList(
            document("same-id", "first", "AI", "ready", 10, new float[]{1, 0, 0})), first).isSuccess());
        assertTrue(store.doStore(Collections.singletonList(
            document("same-id", "second", "Java", "ready", 20, new float[]{1, 0, 0})), second).isSuccess());

        List<Document> firstResult = store.doSearch(vectorSearch(), first);
        List<Document> secondResult = store.doSearch(vectorSearch(), second);

        assertEquals(1, firstResult.size());
        assertEquals("first", firstResult.get(0).getContent());
        assertEquals(1, secondResult.size());
        assertEquals("second", secondResult.get(0).getContent());
        assertNotNull(firstResult.get(0).getScore());
    }

    @Test
    public void shouldApplyComplexPreFilterAndSqlCondition() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        assertTrue(store.doStore(Arrays.asList(
            document("doc-1", "AI document", "AI", "ready", 10, new float[]{1, 0, 0}),
            document("doc-2", "Java document", "Java", "ready", 20, new float[]{0.9f, 0.1f, 0}),
            document("doc-3", "Ops document", "Ops", "deleted", 30, new float[]{0, 1, 0})
        ), options).isSuccess());

        SearchWrapper wrapper = vectorSearch()
            .in("category", Arrays.asList("AI", "Ops"))
            .between("views", 5, 15)
            .isNotNull("category")
            .not(group -> group.eq("status", "deleted"))
            .outputFields("category", "views")
            .outputVector(true)
            .minScore(0.5);
        List<Document> results = store.doSearch(wrapper, options);

        assertEquals(1, results.size());
        assertEquals("doc-1", results.get(0).getId());
        assertEquals("AI", results.get(0).getMetadata("category"));
        assertFalse(results.get(0).containsMetadata("status"));
        assertArrayEquals(new float[]{1, 0, 0}, results.get(0).getVector(), 0.0001f);

        List<Document> filterOnly = store.doSearch(new SearchWrapper()
            .withVector(false)
            .condition("views >= 20 AND category NOT IN ('AI')")
            .maxResults(10), options);
        assertEquals(2, filterOnly.size());
    }

    @Test
    public void shouldSupportOutputUpdateDeleteAndNullQueries() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        Document document = document("doc-1", "before", "AI", "ready", 10,
            new float[]{1, 0, 0});
        assertTrue(store.doStore(Collections.singletonList(document), options).isSuccess());

        Document filtered = store.doSearch(new SearchWrapper()
            .withVector(false)
            .isNull("optional")
            .outputFields("category")
            .maxResults(10), options).get(0);
        assertEquals("AI", filtered.getMetadata("category"));
        assertFalse(filtered.containsMetadata("views"));
        assertNull(filtered.getVector());
        assertNull(filtered.getScore());

        document.setContent("after");
        document.putMetadata("category", "Updated");
        assertTrue(store.doUpdate(Collections.singletonList(document), options).isSuccess());
        Document updated = store.doSearch(vectorSearch().eq("category", "Updated"), options).get(0);
        assertEquals("after", updated.getContent());

        StoreResult deleted = store.doDelete(Collections.singletonList("doc-1"), options);
        assertTrue(deleted.toString(), deleted.isSuccess());
        assertTrue(store.doSearch(new SearchWrapper().withVector(false).maxResults(10), options).isEmpty());
    }

    @Test
    public void shouldRejectStoreWhenAutomaticIndexCreationIsDisabled() {
        MongoDBAtlasVectorStoreConfig config = new MongoDBAtlasVectorStoreConfig();
        config.setConnectionString(System.getProperty("agentsflex.mongodb-atlas.uri",
            "mongodb://127.0.0.1:27018/?directConnection=true"));
        config.setDatabaseName(databaseName);
        config.setDefaultCollectionName("without_index");
        config.setVectorDimension(3);
        config.setAutoCreateVectorIndex(false);
        MongoDBAtlasVectorStore withoutAutoIndex = new MongoDBAtlasVectorStore(config);
        try {
            StoreResult result = withoutAutoIndex.doStore(Collections.singletonList(
                document("doc", "content", "AI", "ready", 1, new float[]{1, 0, 0})),
                StoreOptions.DEFAULT);
            assertFalse(result.isSuccess());
        } finally {
            withoutAutoIndex.close();
        }
    }

    private SearchWrapper vectorSearch() {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(new float[]{1, 0, 0});
        return wrapper;
    }

    private Document document(
        String id,
        String content,
        String category,
        String status,
        int views,
        float[] vector
    ) {
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

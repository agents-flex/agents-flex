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

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PgvectorVectorStoreIntegrationTest {

    private PgvectorVectorStore store;
    private PGSimpleDataSource dataSource;
    private String firstCollection;
    private String secondCollection;

    @Before
    public void setUp() {
        Assume.assumeTrue("Enable with -Dagentsflex.pgvector.integration=true",
            Boolean.getBoolean("agentsflex.pgvector.integration"));

        String host = System.getProperty("agentsflex.pgvector.host", "127.0.0.1");
        int port = Integer.getInteger("agentsflex.pgvector.port", 5432);
        String database = System.getProperty("agentsflex.pgvector.database", "agent_vector");
        String username = System.getProperty("agentsflex.pgvector.username", "agentsflex");
        String password = System.getProperty("agentsflex.pgvector.password", "agentsflex");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        firstCollection = "agents_flex_it_a_" + suffix;
        secondCollection = "agents_flex_it_b_" + suffix;

        PgvectorVectorStoreConfig config = new PgvectorVectorStoreConfig();
        config.setHost(host);
        config.setPort(port);
        config.setDatabaseName(database);
        config.setUsername(username);
        config.setPassword(password);
        config.setDefaultCollectionName(firstCollection);
        config.setVectorDimension(3);
        store = new PgvectorVectorStore(config);

        dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[]{host});
        dataSource.setPortNumbers(new int[]{port});
        dataSource.setDatabaseName(database);
        dataSource.setUser(username);
        dataSource.setPassword(password);
    }

    @After
    public void tearDown() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + PgvectorVectorStore.quoteIdentifier(firstCollection));
            statement.executeUpdate("DROP TABLE IF EXISTS " + PgvectorVectorStore.quoteIdentifier(secondCollection));
        }
    }

    @Test
    public void shouldKeepCollectionsIsolated() {
        assertTrue(store.doStore(Collections.singletonList(document("a-1", "collection-a", "AI", 10,
            new float[]{1, 0, 0})), StoreOptions.ofCollectionName(firstCollection)).isSuccess());
        assertTrue(store.doStore(Collections.singletonList(document("b-1", "collection-b", "Java", 20,
            new float[]{1, 0, 0})), StoreOptions.ofCollectionName(secondCollection)).isSuccess());

        List<Document> first = store.doSearch(search(new float[]{1, 0, 0}),
            StoreOptions.ofCollectionName(firstCollection));
        List<Document> second = store.doSearch(search(new float[]{1, 0, 0}),
            StoreOptions.ofCollectionName(secondCollection));

        assertEquals(1, first.size());
        assertEquals("a-1", first.get(0).getId());
        assertEquals("title-a-1", first.get(0).getTitle());
        assertEquals(1, second.size());
        assertEquals("b-1", second.get(0).getId());
    }

    @Test
    public void shouldApplyInBetweenAndSimilarityThreshold() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        assertTrue(store.doStore(Arrays.asList(
            document("doc-1", "AI", "AI", 10, new float[]{1, 0, 0}),
            document("doc-2", "Java", "Java", 20, new float[]{0.5f, 0.8660254f, 0}),
            document("doc-3", "Ops", "Ops", 30, new float[]{0, 1, 0})
        ), options).isSuccess());

        SearchWrapper filtered = search(new float[]{1, 0, 0})
            .in("metadataMap.category", Arrays.asList("AI", "Ops"))
            .between("views", 5, 15);
        List<Document> filteredResults = store.doSearch(filtered, options);
        assertEquals(1, filteredResults.size());
        assertEquals("doc-1", filteredResults.get(0).getId());

        SearchWrapper highScore = search(new float[]{1, 0, 0}).minScore(0.8);
        List<Document> highScoreResults = store.doSearch(highScore, options);
        assertEquals(1, highScoreResults.size());
        assertEquals("doc-1", highScoreResults.get(0).getId());
        assertEquals(1.0f, highScoreResults.get(0).getScore(), 0.0001f);
    }

    @Test
    public void shouldHonorOutputFieldsAndOutputVector() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        assertTrue(store.doStore(Collections.singletonList(document("doc-1", "content", "AI", 10,
            new float[]{1, 0, 0})), options).isSuccess());

        Document withoutVector = store.doSearch(search(new float[]{1, 0, 0})
            .outputFields("metadataMap.category"), options).get(0);
        assertNull(withoutVector.getVector());
        assertEquals("AI", withoutVector.getMetadata("category"));
        assertFalse(withoutVector.containsMetadata("views"));
        assertNotNull(withoutVector.getScore());

        Document withVector = store.doSearch(search(new float[]{1, 0, 0}).outputVector(true), options).get(0);
        assertEquals(3, withVector.getVector().length);
        assertEquals(10, ((Number) withVector.getMetadata("views")).intValue());
    }

    @Test
    public void shouldBatchUpdateAndDeleteNumericIds() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        Document first = document(1, "before-1", "AI", 10, new float[]{1, 0, 0});
        Document second = document(2, "before-2", "Java", 20, new float[]{0, 1, 0});
        assertTrue(store.doStore(Arrays.asList(first, second), options).isSuccess());

        first.setContent("after-1");
        first.setTitle("updated-title-1");
        second.setContent("after-2");
        assertTrue(store.doUpdate(Arrays.asList(first, second), options).isSuccess());
        Document updatedFirst = store.doSearch(search(new float[]{1, 0, 0}).eq("id", 1), options).get(0);
        assertEquals("after-1", updatedFirst.getContent());
        assertEquals("updated-title-1", updatedFirst.getTitle());
        assertEquals("after-2", store.doSearch(search(new float[]{0, 1, 0}).eq("id", 2), options)
            .get(0).getContent());

        assertTrue(store.doDelete(Arrays.asList(1, 2), options).isSuccess());
        assertTrue(store.doSearch(search(new float[]{1, 0, 0}), options).isEmpty());
        assertTrue(store.doDelete(Collections.emptyList(), options).isSuccess());
    }

    private SearchWrapper search(float[] vector) {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(vector);
        return wrapper;
    }

    private Document document(Object id, String content, String category, int views, float[] vector) {
        Document document = Document.of(content);
        document.setId(id);
        document.setTitle("title-" + id);
        document.setVector(vector);
        document.putMetadata("category", category);
        document.putMetadata("views", views);
        return document;
    }
}

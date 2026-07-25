/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.store.chroma;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class ChromaVectorStoreTest {

    private static final String BASE_URL = "http://127.0.0.1:8000";
    private final List<String> collections = new ArrayList<>();

    @Before
    public void requireRealChroma() {
        Assume.assumeTrue("Enable with -Dagentsflex.chroma.integration=true",
            Boolean.getBoolean("agentsflex.chroma.integration"));
        Assume.assumeTrue(new ChromaVectorStoreConfig().checkAvailable());
    }

    @After
    public void cleanCollections() throws Exception {
        for (String collection : collections) {
            HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL
                + "/api/v2/tenants/default_tenant/databases/default_database/collections/" + collection)
                .openConnection();
            connection.setRequestMethod("DELETE");
            connection.getResponseCode();
            connection.disconnect();
        }
    }

    @Test
    public void shouldKeepCollectionsIsolatedEvenForTheSameDocumentId() {
        ChromaVectorStore store = newStore(true);
        String first = collectionName();
        String second = collectionName();
        StoreOptions firstOptions = StoreOptions.ofCollectionName(first);
        StoreOptions secondOptions = StoreOptions.ofCollectionName(second);

        assertTrue(store.doStore(Arrays.asList(document("same-id", "first", "First", 1, 0,
            "category", "AI")), firstOptions).isSuccess());
        assertTrue(store.doStore(Arrays.asList(document("same-id", "second", "Second", 1, 0,
            "category", "Java")), secondOptions).isSuccess());

        assertEquals("first", search(store, firstOptions).get(0).getContent());
        assertEquals("second", search(store, secondOptions).get(0).getContent());
    }

    @Test
    public void shouldApplyInNinBetweenOutputAndMinScore() {
        ChromaVectorStore store = newStore(true);
        StoreOptions options = StoreOptions.ofCollectionName(collectionName());
        Document matching = document("1", "matching", "Match title", 1, 0,
            "category", "AI", "status", "active", "views", 10, "hidden", "secret");
        Document far = document("2", "far", "Far title", 0, 1,
            "category", "Java", "status", "active", "views", 20, "hidden", "other");
        assertTrue(store.doStore(Arrays.asList(matching, far), options).isSuccess());

        SearchWrapper wrapper = vectorSearch()
            .in("category", Arrays.asList("AI", "Java"))
            .nin("status", Arrays.asList("deleted"))
            .between("views", 5, 15)
            .outputFields("category", "views")
            .outputVector(true)
            .minScore(0.5);
        List<Document> results = store.doSearch(wrapper, options);

        assertEquals(1, results.size());
        Document result = results.get(0);
        assertEquals("1", result.getId());
        assertEquals("Match title", result.getTitle());
        assertArrayEquals(new float[]{1, 0}, result.getVector(), 0.0001f);
        assertEquals("AI", result.getMetadata("category"));
        assertEquals(10, ((Number) result.getMetadata("views")).intValue());
        assertFalse(result.containsMetadata("hidden"));

        SearchWrapper groupedOr = vectorSearch()
            .eq("category", "missing")
            .orCriteria(group -> group.eq("category", "AI").between("views", 5, 15));
        Document groupedResult = store.doSearch(groupedOr, options).get(0);
        assertEquals("1", groupedResult.getId());
        assertNull(groupedResult.getVector());

        try {
            store.doSearch(vectorSearch().eq("category", null), options);
            fail("An invalid condition must not be ignored");
        } catch (StoreException expected) {
            assertTrue(expected.getMessage().contains("null"));
        }
    }

    @Test
    public void shouldUpsertWithoutDeleteAndRestoreTitle() {
        ChromaVectorStore store = newStore(true);
        StoreOptions options = StoreOptions.ofCollectionName(collectionName());
        assertTrue(store.doStore(Arrays.asList(document("1", "before", "Before", 1, 0,
            "version", 1)), options).isSuccess());

        Document updated = document("1", "after", "After", 1, 0, "version", 2);
        Document inserted = document("2", "new", "New", 0.8f, 0.2f, "version", 1);
        assertTrue(store.doUpdate(Arrays.asList(updated, inserted), options).isSuccess());

        List<Document> results = search(store, options);
        assertEquals(2, results.size());
        assertEquals("after", results.get(0).getContent());
        assertEquals("After", results.get(0).getTitle());
        assertEquals(2, ((Number) results.get(0).getMetadata("version")).intValue());
    }

    @Test
    public void shouldRespectDisabledAutoCreation() throws Exception {
        String missing = collectionName();
        ChromaVectorStore store = newStore(false);
        StoreResult result = store.doStore(Arrays.asList(document("1", "content", "title", 1, 0)),
            StoreOptions.ofCollectionName(missing));

        assertFalse(result.isSuccess());
        String collectionsJson = AgentsFlexHttpClient.getDefault().get(BASE_URL
            + "/api/v2/tenants/default_tenant/databases/default_database/collections");
        assertFalse(collectionsJson.contains(missing));
    }

    private ChromaVectorStore newStore(boolean autoCreate) {
        ChromaVectorStoreConfig config = new ChromaVectorStoreConfig();
        config.setHost("127.0.0.1");
        config.setPort(8000);
        config.setCollectionName(collectionName());
        config.setAutoCreateCollection(autoCreate);
        return new ChromaVectorStore(config);
    }

    private String collectionName() {
        String name = "agentsflex_chroma_" + UUID.randomUUID().toString().replace("-", "");
        collections.add(name);
        return name;
    }

    private List<Document> search(ChromaVectorStore store, StoreOptions options) {
        SearchWrapper wrapper = vectorSearch().outputVector(true).maxResults(10);
        return store.doSearch(wrapper, options);
    }

    private SearchWrapper vectorSearch() {
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.setVector(new float[]{1, 0});
        wrapper.setMaxResults(10);
        return wrapper;
    }

    private Document document(String id, String content, String title, float x, float y,
                              Object... metadata) {
        Document document = new Document(content);
        document.setId(id);
        document.setTitle(title);
        document.setVector(new float[]{x, y});
        for (int i = 0; i < metadata.length; i += 2) {
            document.putMetadata(String.valueOf(metadata[i]), metadata[i + 1]);
        }
        return document;
    }
}

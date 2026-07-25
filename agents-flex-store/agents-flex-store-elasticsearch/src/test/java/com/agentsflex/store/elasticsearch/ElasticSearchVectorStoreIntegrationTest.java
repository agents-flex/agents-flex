/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ElasticSearchVectorStoreIntegrationTest {

    private ElasticsearchClient client;
    private ElasticSearchVectorStore store;
    private String firstIndex;
    private String secondIndex;

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("Enable with -Dagentsflex.elasticsearch.integration=true",
            Boolean.getBoolean("agentsflex.elasticsearch.integration"));

        String serverUrl = System.getProperty("agentsflex.elasticsearch.url", "http://127.0.0.1:9200");
        RestClient restClient = RestClient.builder(HttpHost.create(serverUrl)).build();
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        assertTrue(client.ping().value());

        String suffix = UUID.randomUUID().toString().replace("-", "");
        firstIndex = "agents-flex-it-a-" + suffix;
        secondIndex = "agents-flex-it-b-" + suffix;
        ElasticSearchVectorStoreConfig config = new ElasticSearchVectorStoreConfig();
        config.setServerUrl(serverUrl);
        config.setDefaultIndexName(firstIndex);
        store = new ElasticSearchVectorStore(config, client);
    }

    @After
    public void tearDown() throws IOException {
        if (client == null) {
            return;
        }
        deleteIndexIfExists(firstIndex);
        deleteIndexIfExists(secondIndex);
        store.close();
    }

    @Test
    public void shouldKeepCollectionsIsolatedAndSearchImmediately() {
        store.doStore(Collections.singletonList(document("a-1", "collection-a", "AI", 10,
            new float[]{1.0f, 0.0f, 0.0f})), StoreOptions.ofCollectionName(firstIndex));
        store.doStore(Collections.singletonList(document("b-1", "collection-b", "Java", 20,
            new float[]{1.0f, 0.0f, 0.0f})), StoreOptions.ofCollectionName(secondIndex));

        List<Document> firstResults = store.doSearch(search(new float[]{1.0f, 0.0f, 0.0f}),
            StoreOptions.ofCollectionName(firstIndex));
        List<Document> secondResults = store.doSearch(search(new float[]{1.0f, 0.0f, 0.0f}),
            StoreOptions.ofCollectionName(secondIndex));

        assertEquals(1, firstResults.size());
        assertEquals("a-1", firstResults.get(0).getId());
        assertEquals(1, secondResults.size());
        assertEquals("b-1", secondResults.get(0).getId());
    }

    @Test
    public void shouldApplyInAndRangeConditionsOnRealElasticsearch() {
        StoreOptions options = StoreOptions.ofCollectionName(firstIndex);
        store.doStore(Arrays.asList(
            document("doc-1", "AI document", "AI", 10, new float[]{1.0f, 0.0f, 0.0f}),
            document("doc-2", "Java document", "Java", 20, new float[]{0.9f, 0.1f, 0.0f}),
            document("doc-3", "Ops document", "Ops", 30, new float[]{0.0f, 1.0f, 0.0f})
        ), options);

        SearchWrapper wrapper = search(new float[]{1.0f, 0.0f, 0.0f})
            .in("metadataMap.category", Arrays.asList("AI", "Ops"))
            .between("metadataMap.views", 5, 15);
        List<Document> results = store.doSearch(wrapper, options);

        assertEquals(1, results.size());
        assertEquals("doc-1", results.get(0).getId());
    }

    @Test
    public void shouldHonorOutputUpdateAndDeleteOptionsImmediately() {
        StoreOptions options = StoreOptions.ofCollectionName(firstIndex);
        Document document = document("doc-1", "before update", "AI", 10,
            new float[]{1.0f, 0.0f, 0.0f});
        store.doStore(Collections.singletonList(document), options);

        SearchWrapper withoutVector = search(new float[]{1.0f, 0.0f, 0.0f})
            .outputFields("metadataMap.category");
        Document result = store.doSearch(withoutVector, options).get(0);
        assertNull(result.getVector());
        assertEquals("AI", result.getMetadata("category"));
        assertFalse(result.containsMetadata("views"));

        document.setContent("after update");
        document.putMetadata("category", "Updated");
        store.doUpdate(Collections.singletonList(document), options);
        List<Document> updated = store.doSearch(search(new float[]{1.0f, 0.0f, 0.0f})
            .eq("metadataMap.category", "Updated")
            .outputVector(true), options);
        assertEquals(1, updated.size());
        assertEquals("after update", updated.get(0).getContent());
        assertEquals(3, updated.get(0).getVector().length);

        store.doDelete(Collections.singletonList("doc-1"), options);
        assertTrue(store.doSearch(search(new float[]{1.0f, 0.0f, 0.0f}), options).isEmpty());
    }

    private SearchWrapper search(float[] vector) {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(vector);
        return wrapper;
    }

    private Document document(String id, String content, String category, int views, float[] vector) {
        Document document = Document.of(content);
        document.setId(id);
        document.setVector(vector);
        document.putMetadata("category", category);
        document.putMetadata("views", views);
        return document;
    }

    private void deleteIndexIfExists(String indexName) throws IOException {
        if (indexName != null && client.indices().exists(request -> request.index(indexName)).value()) {
            client.indices().delete(request -> request.index(indexName));
        }
    }
}

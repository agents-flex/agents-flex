/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.opensearch;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.apache.hc.core5.http.HttpHost;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpenSearchVectorStoreIntegrationTest {

    private OpenSearchClient client;
    private OpenSearchVectorStore store;
    private String firstIndex;
    private String secondIndex;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("Enable with -Dagentsflex.opensearch.integration=true",
            Boolean.getBoolean("agentsflex.opensearch.integration"));

        String serverUrl = System.getProperty("agentsflex.opensearch.url", "http://127.0.0.1:9201");
        OpenSearchTransport transport = ApacheHttpClient5TransportBuilder.builder(HttpHost.create(serverUrl))
            .setMapper(new JacksonJsonpMapper()).build();
        client = new OpenSearchClient(transport);
        assertTrue(client.ping().value());

        String suffix = UUID.randomUUID().toString().replace("-", "");
        firstIndex = "agents-flex-os-it-a-" + suffix;
        secondIndex = "agents-flex-os-it-b-" + suffix;
        OpenSearchVectorStoreConfig config = new OpenSearchVectorStoreConfig();
        config.setServerUrl(serverUrl);
        config.setDefaultIndexName(firstIndex);
        store = new OpenSearchVectorStore(config);
    }

    @After
    public void tearDown() throws IOException {
        if (client == null) {
            return;
        }
        deleteIndexIfExists(firstIndex);
        deleteIndexIfExists(secondIndex);
        if (store != null) {
            store.close();
        }
        client._transport().close();
    }

    @Test
    public void shouldKeepCollectionsIsolatedAndSearchImmediately() {
        store.doStore(Collections.singletonList(document("a-1", "collection-a", "AI", 10,
            new float[]{1.0f, 0.0f, 0.0f})), StoreOptions.ofCollectionName(firstIndex));
        store.doStore(Collections.singletonList(document("b-1", "collection-b", "Java", 20,
            new float[]{1.0f, 0.0f, 0.0f})), StoreOptions.ofCollectionName(secondIndex));

        List<Document> first = store.doSearch(search(), StoreOptions.ofCollectionName(firstIndex));
        List<Document> second = store.doSearch(search(), StoreOptions.ofCollectionName(secondIndex));

        assertEquals(1, first.size());
        assertEquals("a-1", first.get(0).getId());
        assertEquals(1, second.size());
        assertEquals("b-1", second.get(0).getId());
    }

    @Test
    public void shouldApplyComplexConditionsAndFilterOnlyQueries() {
        StoreOptions options = StoreOptions.ofCollectionName(firstIndex);
        store.doStore(Arrays.asList(
            document("doc-1", "AI document", "AI", 10, new float[]{1.0f, 0.0f, 0.0f}),
            document("doc-2", "Java document", "Java", 20, new float[]{0.9f, 0.1f, 0.0f}),
            document("doc-3", "Ops document", "Ops", 30, new float[]{0.0f, 1.0f, 0.0f})
        ), options);

        SearchWrapper vectorQuery = search()
            .in("metadataMap.category", Arrays.asList("AI", "Ops"))
            .between("metadataMap.views", 5, 15)
            .isNotNull("metadataMap.category")
            .not(group -> group.eq("metadataMap.category", "Java"));
        List<Document> vectorResults = store.doSearch(vectorQuery, options);
        assertEquals(1, vectorResults.size());
        assertEquals("doc-1", vectorResults.get(0).getId());

        List<Document> filtered = store.doSearch(new SearchWrapper().withVector(false)
            .condition("metadataMap.views >= 20 AND metadataMap.category NOT IN ('AI')")
            .maxResults(10), options);
        assertEquals(2, filtered.size());
    }

    @Test
    public void shouldHonorOutputUpdateAndDeleteImmediately() {
        StoreOptions options = StoreOptions.ofCollectionName(firstIndex);
        Document document = document("doc-1", "before update", "AI", 10,
            new float[]{1.0f, 0.0f, 0.0f});
        store.doStore(Collections.singletonList(document), options);

        Document result = store.doSearch(search().outputFields("metadataMap.category"), options).get(0);
        assertNull(result.getVector());
        assertEquals("AI", result.getMetadata("category"));
        assertFalse(result.containsMetadata("views"));

        document.setContent("after update");
        document.putMetadata("category", "Updated");
        store.doUpdate(Collections.singletonList(document), options);
        List<Document> updated = store.doSearch(search()
            .eq("metadataMap.category", "Updated").outputVector(true), options);
        assertEquals(1, updated.size());
        assertEquals("after update", updated.get(0).getContent());
        assertEquals(3, updated.get(0).getVector().length);

        store.doDelete(Collections.singletonList("doc-1"), options);
        assertTrue(store.doSearch(search(), options).isEmpty());
    }

    private SearchWrapper search() {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(new float[]{1.0f, 0.0f, 0.0f});
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

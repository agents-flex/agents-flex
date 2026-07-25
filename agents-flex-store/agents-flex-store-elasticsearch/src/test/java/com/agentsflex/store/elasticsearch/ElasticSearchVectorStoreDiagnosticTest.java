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
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.Endpoint;
import co.elastic.clients.transport.TransportOptions;
import co.elastic.clients.transport.rest_client.RestClientOptions;
import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import org.elasticsearch.client.RequestOptions;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ElasticSearchVectorStoreDiagnosticTest {

    @Test
    public void shouldApplySearchWrapperConditions() {
        CapturingClient client = new CapturingClient(Collections.emptyList());
        ElasticSearchVectorStore store = store(client);
        SearchWrapper wrapper = search();
        wrapper.eq("metadataMap.category", "AI");
        wrapper.in("metadataMap.views", Arrays.asList(10, 20));

        store.doSearch(wrapper, StoreOptions.DEFAULT);

        assertTrue(client.searchRequest.query().scriptScore().query().isQueryString());
        assertEquals("metadataMap.category:\"AI\" AND metadataMap.views:(10 OR 20)",
            client.searchRequest.query().scriptScore().query().queryString().query());
        assertTrue(client.searchRequest.toString().contains("query_string"));
    }

    @Test
    public void shouldUseCollectionNameConsistentlyForSearchAndDelete() {
        CapturingClient client = new CapturingClient(Collections.emptyList());
        ElasticSearchVectorStore store = store(client);
        StoreOptions options = StoreOptions.ofCollectionName("tenant-index");

        store.doSearch(search(), options);
        store.doDelete(Collections.singletonList("doc-1"), options);

        assertEquals("tenant-index", client.searchRequest.index().get(0));
        assertEquals("tenant-index", client.bulkRequest.operations().get(0).delete().index());
        assertEquals(Refresh.WaitFor, client.bulkRequest.refresh());

        Document document = Document.of("updated");
        document.setId("doc-1");
        document.setVector(new float[]{1.0f, 0.0f});
        store.doUpdate(Collections.singletonList(document), options);
        assertEquals("tenant-index", client.bulkRequest.operations().get(0).index().index());
        assertEquals(Refresh.WaitFor, client.bulkRequest.refresh());
    }

    @Test
    public void shouldHonorOutputVectorFlag() {
        Map<String, Object> source = new HashMap<>();
        source.put("id", "doc-1");
        source.put("content", "content");
        source.put("vector", Arrays.asList(1.0, 0.0));
        CapturingClient client = new CapturingClient(Collections.singletonList(source));
        ElasticSearchVectorStore store = store(client);

        List<Document> documents = store.doSearch(search(), StoreOptions.DEFAULT);

        assertEquals(1, documents.size());
        assertNull(documents.get(0).getVector());
    }

    @Test
    public void shouldApplyRequestedOutputFields() {
        CapturingClient client = new CapturingClient(Collections.emptyList());
        ElasticSearchVectorStore store = store(client);
        SearchWrapper wrapper = search();
        wrapper.outputFields("content", "metadataMap.category");

        store.doSearch(wrapper, StoreOptions.DEFAULT);

        assertNotNull(client.searchRequest.source());
        assertTrue(client.searchRequest.source().isFilter());
        assertTrue(client.searchRequest.source().filter().includes().contains("content"));
        assertTrue(client.searchRequest.source().filter().includes().contains("metadataMap.category"));
        assertFalse(client.searchRequest.source().filter().includes().contains("vector"));
    }

    @Test
    public void shouldAcceptUsernamePasswordConfigurationWithoutApiKey() {
        ElasticSearchVectorStoreConfig config = new ElasticSearchVectorStoreConfig();
        config.setServerUrl("https://localhost:9200");
        config.setApiKey(null);
        config.setUsername("elastic");
        config.setPassword("secret");

        assertTrue(config.checkAvailable());
    }

    @Test
    public void shouldAcceptConfigurationWithoutAuthentication() {
        ElasticSearchVectorStoreConfig config = new ElasticSearchVectorStoreConfig();
        config.setServerUrl("http://localhost:9200");
        config.setApiKey(null);
        config.setUsername(null);
        config.setPassword(null);

        assertTrue(config.checkAvailable());

        config.setUsername("elastic");
        assertFalse(config.checkAvailable());
    }

    @Test
    public void shouldPassMinScoreAndMaxResultsToSearchRequest() {
        CapturingClient client = new CapturingClient(Collections.emptyList());
        ElasticSearchVectorStore store = store(client);
        SearchWrapper wrapper = search();
        wrapper.setMinScore(0.7d);
        wrapper.setMaxResults(8);

        store.doSearch(wrapper, StoreOptions.DEFAULT);

        assertEquals(Float.valueOf(0.7f), client.searchRequest.query().scriptScore().minScore());
        assertEquals(Integer.valueOf(8), client.searchRequest.size());
    }

    @Test
    public void shouldInferIndexDimensionAndRefreshBulkWrites() {
        NoOpTransport transport = new NoOpTransport();
        CapturingClient client = new CapturingClient(Collections.emptyList(), transport);
        ElasticSearchVectorStore store = store(client);
        Document document = Document.of("content");
        document.setId("doc-1");
        document.setVector(new float[]{1.0f, 0.0f, 0.5f});

        store.doStore(Collections.singletonList(document), StoreOptions.DEFAULT);

        assertNotNull(transport.createIndexRequest);
        assertEquals(Integer.valueOf(3), transport.createIndexRequest.mappings()
            .properties().get("vector").denseVector().dims());
        assertEquals(Refresh.WaitFor, client.bulkRequest.refresh());
    }

    @Test
    public void shouldSkipEmptyBulkOperations() {
        CapturingClient client = new CapturingClient(Collections.emptyList());
        ElasticSearchVectorStore store = store(client);

        store.doStore(Collections.emptyList(), StoreOptions.DEFAULT);
        store.doUpdate(Collections.emptyList(), StoreOptions.DEFAULT);
        store.doDelete(Collections.emptyList(), StoreOptions.DEFAULT);

        assertNull(client.bulkRequest);
    }

    @Test
    public void shouldCloseClientTransport() {
        NoOpTransport transport = new NoOpTransport();
        ElasticSearchVectorStore store = store(new CapturingClient(Collections.emptyList(), transport));

        store.close();

        assertTrue(transport.closed);
    }

    private ElasticSearchVectorStore store(CapturingClient client) {
        ElasticSearchVectorStoreConfig config = new ElasticSearchVectorStoreConfig();
        config.setDefaultIndexName("default-index");
        return new ElasticSearchVectorStore(config, client);
    }

    private SearchWrapper search() {
        SearchWrapper wrapper = new SearchWrapper();
        wrapper.setVector(new float[]{1.0f, 0.0f});
        wrapper.setMaxResults(10);
        return wrapper;
    }

    private static class CapturingClient extends ElasticsearchClient {
        private final List<Map<String, Object>> sources;
        private SearchRequest searchRequest;
        private BulkRequest bulkRequest;

        private CapturingClient(List<Map<String, Object>> sources) {
            this(sources, new NoOpTransport());
        }

        private CapturingClient(List<Map<String, Object>> sources, NoOpTransport transport) {
            super(transport);
            this.sources = sources;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <TDocument> SearchResponse<TDocument> search(SearchRequest request, Class<TDocument> documentClass) {
            this.searchRequest = request;
            List<Hit<TDocument>> hits = new java.util.ArrayList<>();
            for (Map<String, Object> source : sources) {
                hits.add((Hit<TDocument>) Hit.of(hit -> hit
                    .index(request.index().get(0))
                    .id(String.valueOf(source.get("id")))
                    .score(1.0)
                    .source(JsonData.of(source))));
            }
            return SearchResponse.of(response -> response
                .took(1)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(metadata -> metadata.hits(hits)));
        }

        @Override
        public BulkResponse bulk(BulkRequest request) {
            this.bulkRequest = request;
            return BulkResponse.of(response -> response.errors(false).items(Collections.emptyList()).took(1));
        }
    }

    private static class NoOpTransport implements ElasticsearchTransport {
        private final JsonpMapper mapper = new JacksonJsonpMapper();
        private final TransportOptions options = new RestClientOptions(RequestOptions.DEFAULT);
        private CreateIndexRequest createIndexRequest;
        private boolean closed;

        @Override
        @SuppressWarnings("unchecked")
        public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) throws IOException {
            if ("es/indices.exists".equals(endpoint.id())) {
                return (ResponseT) new co.elastic.clients.transport.endpoints.BooleanResponse(false);
            }
            if ("es/indices.create".equals(endpoint.id())) {
                createIndexRequest = (CreateIndexRequest) request;
                return (ResponseT) CreateIndexResponse.of(response -> response
                    .index(createIndexRequest.index())
                    .acknowledged(true)
                    .shardsAcknowledged(true));
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
            CompletableFuture<ResponseT> future = new CompletableFuture<>();
            future.completeExceptionally(new UnsupportedOperationException());
            return future;
        }

        @Override
        public JsonpMapper jsonpMapper() {
            return mapper;
        }

        @Override
        public TransportOptions options() {
            return options;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

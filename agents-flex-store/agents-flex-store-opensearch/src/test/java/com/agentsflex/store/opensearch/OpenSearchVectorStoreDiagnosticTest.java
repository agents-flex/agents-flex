/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.opensearch;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.transport.Endpoint;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.TransportOptions;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpenSearchVectorStoreDiagnosticTest {

    @Test
    public void shouldApplyConditionsInsideVectorQuery() {
        CapturingClient client = new CapturingClient();
        OpenSearchVectorStore store = store(client);
        SearchWrapper wrapper = search()
            .eq("metadataMap.category", "AI")
            .in("metadataMap.views", Arrays.asList(10, 20));

        store.doSearch(wrapper, StoreOptions.DEFAULT);

        assertTrue(client.searchRequest.query().isScriptScore());
        assertTrue(client.searchRequest.query().scriptScore().query().isQueryString());
        assertEquals("metadataMap.category:\"AI\" AND metadataMap.views:(10 OR 20)",
            client.searchRequest.query().scriptScore().query().queryString().query());
    }

    @Test
    public void shouldSupportFilterOnlySearch() {
        CapturingClient client = new CapturingClient();
        OpenSearchVectorStore store = store(client);

        store.doSearch(new SearchWrapper().withVector(false).eq("metadataMap.status", "ready"),
            StoreOptions.DEFAULT);

        assertTrue(client.searchRequest.query().isQueryString());
        assertEquals("metadataMap.status:\"ready\"", client.searchRequest.query().queryString().query());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectVectorSearchWithoutVector() {
        store(new CapturingClient()).doSearch(new SearchWrapper(), StoreOptions.DEFAULT);
    }

    @Test
    public void shouldUseCollectionNameAndRefreshBulkOperations() {
        CapturingClient client = new CapturingClient();
        OpenSearchVectorStore store = store(client);
        StoreOptions options = StoreOptions.ofCollectionName("tenant-index");
        Document document = document("doc-1", 3);

        store.doStore(Collections.singletonList(document), options);
        assertEquals("tenant-index", client.bulkRequest.operations().get(0).index().index());
        assertEquals(Refresh.WaitFor, client.bulkRequest.refresh());

        store.doSearch(search(), options);
        assertEquals("tenant-index", client.searchRequest.index().get(0));

        store.doUpdate(Collections.singletonList(document), options);
        assertEquals("tenant-index", client.bulkRequest.operations().get(0).index().index());
        assertEquals(Refresh.WaitFor, client.bulkRequest.refresh());

        store.doDelete(Collections.singletonList("doc-1"), options);
        assertEquals("tenant-index", client.bulkRequest.operations().get(0).delete().index());
        assertEquals(Refresh.WaitFor, client.bulkRequest.refresh());
    }

    @Test
    public void shouldInferDimensionAndSkipEmptyBulkOperations() {
        NoOpTransport transport = new NoOpTransport();
        CapturingClient client = new CapturingClient(transport);
        OpenSearchVectorStore store = store(client);

        store.doStore(Collections.singletonList(document("doc-1", 3)), StoreOptions.DEFAULT);

        assertNotNull(transport.createIndexRequest);
        assertEquals(3, transport.createIndexRequest.mappings()
            .properties().get("vector").knnVector().dimension());

        client.bulkRequest = null;
        store.doStore(Collections.emptyList(), StoreOptions.DEFAULT);
        store.doUpdate(Collections.emptyList(), StoreOptions.DEFAULT);
        store.doDelete(Collections.emptyList(), StoreOptions.DEFAULT);
        assertNull(client.bulkRequest);
    }

    @Test
    public void shouldApplyOutputFieldsAndVectorFlag() {
        CapturingClient client = new CapturingClient();
        OpenSearchVectorStore store = store(client);
        SearchWrapper wrapper = search().outputFields("metadataMap.category");

        store.doSearch(wrapper, StoreOptions.DEFAULT);

        assertNotNull(client.searchRequest.source());
        assertTrue(client.searchRequest.source().isFilter());
        assertTrue(client.searchRequest.source().filter().includes().contains("id"));
        assertTrue(client.searchRequest.source().filter().includes().contains("content"));
        assertTrue(client.searchRequest.source().filter().includes().contains("metadataMap.category"));
        assertFalse(client.searchRequest.source().filter().includes().contains("vector"));

        store.doSearch(search().outputVector(true), StoreOptions.DEFAULT);
        assertNull(client.searchRequest.source());
    }

    @Test
    public void shouldAcceptNoAuthenticationAndRejectPartialBasicAuth() {
        OpenSearchVectorStoreConfig config = new OpenSearchVectorStoreConfig();
        config.setServerUrl("http://localhost:9201");
        config.setApiKey(null);
        config.setUsername(null);
        config.setPassword(null);
        assertTrue(config.checkAvailable());

        config.setUsername("admin");
        assertFalse(config.checkAvailable());
    }

    @Test
    public void shouldPassScoreAndLimitAndCloseTransport() {
        NoOpTransport transport = new NoOpTransport();
        CapturingClient client = new CapturingClient(transport);
        OpenSearchVectorStore store = store(client);
        SearchWrapper wrapper = search().minScore(0.7d).maxResults(8);

        store.doSearch(wrapper, StoreOptions.DEFAULT);
        assertEquals(Float.valueOf(0.7f), client.searchRequest.query().scriptScore().minScore());
        assertEquals(Integer.valueOf(8), client.searchRequest.size());

        store.close();
        assertTrue(transport.closed);
    }

    private OpenSearchVectorStore store(CapturingClient client) {
        OpenSearchVectorStoreConfig config = new OpenSearchVectorStoreConfig();
        config.setDefaultIndexName("default-index");
        return new OpenSearchVectorStore(config, client);
    }

    private SearchWrapper search() {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(new float[]{1.0f, 0.0f});
        return wrapper;
    }

    private Document document(String id, int dimension) {
        Document document = Document.of("content");
        document.setId(id);
        document.setVector(new float[dimension]);
        return document;
    }

    private static class CapturingClient extends OpenSearchClient {
        private SearchRequest searchRequest;
        private BulkRequest bulkRequest;

        private CapturingClient() {
            this(new NoOpTransport());
        }

        private CapturingClient(NoOpTransport transport) {
            super(transport);
        }

        @Override
        public <TDocument> SearchResponse<TDocument> search(SearchRequest request, Class<TDocument> documentClass) {
            this.searchRequest = request;
            List<Hit<TDocument>> hits = new ArrayList<>();
            return new SearchResponse.Builder<TDocument>()
                .took(1)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(metadata -> metadata.hits(hits))
                .build();
        }

        @Override
        public BulkResponse bulk(BulkRequest request) {
            this.bulkRequest = request;
            return BulkResponse.of(response -> response.errors(false).items(Collections.emptyList()).took(1));
        }
    }

    private static class NoOpTransport implements OpenSearchTransport {
        private final JsonpMapper mapper = new JacksonJsonpMapper();
        private final TransportOptions options = TransportOptions.builder().build();
        private CreateIndexRequest createIndexRequest;
        private boolean closed;

        @Override
        @SuppressWarnings("unchecked")
        public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options) {
            if (request instanceof org.opensearch.client.opensearch.indices.ExistsRequest) {
                return (ResponseT) new org.opensearch.client.transport.endpoints.BooleanResponse(false);
            }
            if (request instanceof CreateIndexRequest) {
                createIndexRequest = (CreateIndexRequest) request;
                return (ResponseT) CreateIndexResponse.of(response -> response
                    .index(createIndexRequest.index()).acknowledged(true).shardsAcknowledged(true));
            }
            throw new UnsupportedOperationException(request.getClass().getName());
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
        public void close() throws IOException {
            closed = true;
        }
    }
}

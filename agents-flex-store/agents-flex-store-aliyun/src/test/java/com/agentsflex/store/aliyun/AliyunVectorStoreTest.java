/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.aliyun;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.aliyun.dashvector.models.Doc;
import com.aliyun.dashvector.models.DocOpResult;
import com.aliyun.dashvector.models.Vector;
import com.aliyun.dashvector.models.requests.DeleteDocRequest;
import com.aliyun.dashvector.models.requests.QueryDocRequest;
import com.aliyun.dashvector.models.requests.UpdateDocRequest;
import com.aliyun.dashvector.models.requests.UpsertDocRequest;
import com.aliyun.dashvector.models.responses.Response;
import com.aliyun.dashvector.proto.CollectionInfo;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AliyunVectorStoreTest {

    @Test
    public void configShouldNotRequireUnusedDatabase() {
        AliyunVectorStoreConfig config = config();
        assertTrue(config.checkAvailable());

        config.setTimeout(null);
        assertFalse(config.checkAvailable());

        config.setTimeout(0.0f);
        assertFalse(config.checkAvailable());

        config.setTimeout(Float.NaN);
        assertFalse(config.checkAvailable());
    }

    @Test
    public void shouldMapUpsertUpdateDeleteAndCollectionPartition() {
        FakeClient client = new FakeClient();
        AliyunVectorStore store = new AliyunVectorStore(config(), client);
        StoreOptions options = StoreOptions.ofCollectionName("tenant_docs");
        options.partitionName("release_2026");

        Document document = document("doc-1", 0.1f);
        StoreResult stored = store.store(document, options);

        assertTrue(stored.toString(), stored.isSuccess());
        assertEquals(Collections.<Object>singletonList("doc-1"), stored.getIds());
        assertEquals("tenant_docs", client.collectionName);
        assertEquals("release_2026", client.upsertRequest.getPartition());
        Doc sdkDoc = client.upsertRequest.getDocs().get(0);
        assertEquals("doc-1", sdkDoc.getId());
        assertEquals("正文-doc-1", sdkDoc.getFields().get(AliyunVectorStore.FIELD_CONTENT));
        assertEquals("标题-doc-1", sdkDoc.getFields().get(AliyunVectorStore.FIELD_TITLE));
        assertEquals("guide", sdkDoc.getFields().get("category"));
        assertEquals(0.1f, sdkDoc.getVector().getValue().get(0).floatValue(), 0.0001f);

        document.setVector(null);
        StoreResult updated = store.update(document, options);
        assertTrue(updated.toString(), updated.isSuccess());
        assertNull(client.updateRequest.getDocs().get(0).getVector());
        assertEquals("release_2026", client.updateRequest.getPartition());

        StoreResult deleted = store.delete(Arrays.asList("doc-1", 2L), options);
        assertTrue(deleted.toString(), deleted.isSuccess());
        assertEquals(Arrays.asList("doc-1", "2"), client.deleteRequest.getIds());
        assertEquals("release_2026", client.deleteRequest.getPartition());
    }

    @Test
    public void shouldRouteEachOperationToItsOwnCollection() {
        FakeClient client = new FakeClient();
        AliyunVectorStore store = new AliyunVectorStore(config(), client);

        StoreResult first = store.store(
            document("doc-a", 0.1f),
            StoreOptions.ofCollectionName("collection_a"));
        StoreResult second = store.store(
            document("doc-b", 0.2f),
            StoreOptions.ofCollectionName("collection_b"));

        assertTrue(first.toString(), first.isSuccess());
        assertTrue(second.toString(), second.isSuccess());
        assertEquals(Arrays.asList("collection_a", "collection_b"), client.collectionNames);
    }

    @Test
    public void shouldMapSearchRequestAndRestoreDocument() {
        FakeClient client = new FakeClient();
        client.queryMetric = CollectionInfo.Metric.cosine;
        client.queryResponse = success(Arrays.asList(
            sdkDocument("doc-1", 0.4f),
            sdkDocument("doc-2", 1.0f)
        ));
        AliyunVectorStore store = new AliyunVectorStore(config(), client);

        SearchWrapper query = new SearchWrapper()
            .maxResults(8)
            .minScore(0.7)
            .eq("tenant", "tenant-a")
            .outputFields("category")
            .outputVector(true);
        query.setVector(new float[]{0.1f, 0.2f});

        StoreOptions options = StoreOptions.ofCollectionName("search_docs");
        options.partitionName("partition-a");
        List<Document> result = store.search(query, options);

        assertEquals("search_docs", client.collectionName);
        assertEquals(8, client.queryRequest.getTopk());
        assertTrue(client.queryRequest.isIncludeVector());
        assertEquals("partition-a", client.queryRequest.getPartition());
        assertEquals("tenant = 'tenant-a'", client.queryRequest.getFilter());
        assertEquals(Arrays.asList(
            "category",
            AliyunVectorStore.FIELD_CONTENT,
            AliyunVectorStore.FIELD_TITLE
        ), client.queryRequest.getOutputFields());
        assertEquals(0.1f, client.queryRequest.getVector().getValue().get(0).floatValue(), 0.0001f);

        assertEquals(1, result.size());
        Document document = result.get(0);
        assertEquals("doc-1", document.getId());
        assertEquals("正文-doc-1", document.getContent());
        assertEquals("标题-doc-1", document.getTitle());
        assertEquals("guide", document.getMetadata("category"));
        assertNull(document.getMetadata(AliyunVectorStore.FIELD_CONTENT));
        assertEquals(0.8f, document.getScore(), 0.0001f);
        assertArrayEquals(new float[]{0.3f, 0.4f}, document.getVector(), 0.0001f);
    }

    @Test
    public void shouldSupportFilterOnlyQuery() {
        FakeClient client = new FakeClient();
        AliyunVectorStore store = new AliyunVectorStore(config(), client);

        SearchWrapper query = new SearchWrapper()
            .withVector(false)
            .eq("tenant", "tenant-a")
            .maxResults(20);
        store.search(query);

        assertNull(client.queryRequest.getVector());
        assertEquals("tenant = 'tenant-a'", client.queryRequest.getFilter());
        assertFalse(client.queryRequest.isIncludeVector());
    }

    @Test
    public void shouldKeepInvalidQueryAsArgumentError() {
        FakeClient client = new FakeClient();
        AliyunVectorStore store = new AliyunVectorStore(config(), client);
        SearchWrapper query = new SearchWrapper()
            .withVector(false)
            .condition("NOT (status = 'deleted')");

        try {
            store.search(query);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unary NOT"));
        }
        assertNull(client.queryRequest);
    }

    @Test
    public void shouldRejectReservedFieldsAndMultiplePartitions() {
        FakeClient client = new FakeClient();
        AliyunVectorStore store = new AliyunVectorStore(config(), client);
        Document document = document("doc-1", 0.1f);
        document.putMetadata(AliyunVectorStore.FIELD_CONTENT, "collision");

        StoreResult result = store.store(document);
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("reserved"));

        StoreOptions options = StoreOptions.ofCollectionName("docs");
        options.partitionName("a").partitionName("b");
        result = store.store(document("doc-2", 0.2f), options);
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("one partition"));
    }

    @Test
    public void shouldNormalizeSupportedNumbersAndRejectUnsupportedMetadata() {
        FakeClient client = new FakeClient();
        AliyunVectorStore store = new AliyunVectorStore(config(), client);
        Document document = document("doc-1", 0.1f);
        document.putMetadata("ratio", 1.25d);
        document.putMetadata("small", (short) 3);

        StoreResult result = store.store(document);
        assertTrue(result.toString(), result.isSuccess());
        Map<String, Object> fields = client.upsertRequest.getDocs().get(0).getFields();
        assertTrue(fields.get("ratio") instanceof Float);
        assertTrue(fields.get("small") instanceof Integer);

        document.putMetadata("unsupported", Arrays.asList("a", "b"));
        result = store.store(document);
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Unsupported DashVector metadata"));
    }

    @Test
    public void shouldReportSdkAndPartialFailures() {
        FakeClient client = new FakeClient();
        client.writeResponse = Response.create(
            0,
            "Success",
            "request-1",
            Collections.singletonList(DocOpResult.builder()
                .id("doc-1")
                .code(7)
                .message("item failed")
                .build())
        );
        AliyunVectorStore store = new AliyunVectorStore(config(), client);

        StoreResult result = store.store(document("doc-1", 0.1f));
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("partially failed"));
        assertTrue(result.getMessage().contains("request-1"));

        client.queryResponse = Response.create(3, "query failed", "request-2", null);
        try {
            SearchWrapper query = new SearchWrapper().withVector(false).eq("tenant", "a");
            store.search(query);
            fail("Expected StoreException");
        } catch (StoreException expected) {
            assertTrue(expected.getMessage().contains("request-2"));
        }
    }

    @Test
    public void shouldCloseInjectedClient() {
        FakeClient client = new FakeClient();
        AliyunVectorStore store = new AliyunVectorStore(config(), client);
        store.close();
        assertTrue(client.closed);
    }

    private AliyunVectorStoreConfig config() {
        AliyunVectorStoreConfig config = new AliyunVectorStoreConfig();
        config.setEndpoint("example.dashvector.aliyuncs.com");
        config.setApiKey("test-api-key");
        config.setDefaultCollectionName("default_docs");
        return config;
    }

    private Document document(String id, float value) {
        Document document = Document.of("正文-" + id);
        document.setId(id);
        document.setTitle("标题-" + id);
        document.setVector(new float[]{value, value + 0.1f});
        document.putMetadata("category", "guide");
        return document;
    }

    private Doc sdkDocument(String id, float distance) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category", "guide");
        fields.put(AliyunVectorStore.FIELD_CONTENT, "正文-" + id);
        fields.put(AliyunVectorStore.FIELD_TITLE, "标题-" + id);
        return Doc.builder()
            .id(id)
            .vector(Vector.builder().value(Arrays.asList(0.3f, 0.4f)).build())
            .fields(fields)
            .score(distance)
            .build();
    }

    private static <T> Response<List<T>> success(List<T> output) {
        return Response.create(0, "Success", "request-ok", output);
    }

    private static class FakeClient implements AliyunVectorClient {
        private String collectionName;
        private final List<String> collectionNames = new ArrayList<>();
        private UpsertDocRequest upsertRequest;
        private UpdateDocRequest updateRequest;
        private DeleteDocRequest deleteRequest;
        private QueryDocRequest queryRequest;
        private Response<List<DocOpResult>> writeResponse = success(Collections.<DocOpResult>emptyList());
        private Response<List<Doc>> queryResponse = success(Collections.<Doc>emptyList());
        private CollectionInfo.Metric queryMetric = CollectionInfo.Metric.cosine;
        private boolean closed;

        @Override
        public Response<List<DocOpResult>> upsert(String collectionName, UpsertDocRequest request) {
            this.collectionName = collectionName;
            this.collectionNames.add(collectionName);
            this.upsertRequest = request;
            return writeResponse;
        }

        @Override
        public Response<List<DocOpResult>> update(String collectionName, UpdateDocRequest request) {
            this.collectionName = collectionName;
            this.collectionNames.add(collectionName);
            this.updateRequest = request;
            return writeResponse;
        }

        @Override
        public Response<List<DocOpResult>> delete(String collectionName, DeleteDocRequest request) {
            this.collectionName = collectionName;
            this.collectionNames.add(collectionName);
            this.deleteRequest = request;
            return writeResponse;
        }

        @Override
        public QueryResult query(String collectionName, QueryDocRequest request) {
            this.collectionName = collectionName;
            this.collectionNames.add(collectionName);
            this.queryRequest = request;
            return new QueryResult(queryResponse, queryMetric);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

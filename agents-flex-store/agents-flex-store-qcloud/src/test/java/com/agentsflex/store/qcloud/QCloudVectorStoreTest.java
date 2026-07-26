/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.qcloud;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.tencent.tcvectordb.model.DocField;
import com.tencent.tcvectordb.model.param.dml.DeleteParam;
import com.tencent.tcvectordb.model.param.dml.InsertParam;
import com.tencent.tcvectordb.model.param.dml.QueryParam;
import com.tencent.tcvectordb.model.param.dml.SearchByVectorParam;
import com.tencent.tcvectordb.model.param.dml.UpdateParam;
import com.tencent.tcvectordb.model.param.entity.AffectRes;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QCloudVectorStoreTest {

    @Test
    public void configShouldValidateSdkConnectionSettings() {
        QCloudVectorStoreConfig config = config();
        assertTrue(config.checkAvailable());
        config.setTimeout(0);
        assertFalse(config.checkAvailable());
        config.setTimeout(10);
        config.setConnectTimeout(-1);
        assertFalse(config.checkAvailable());
    }

    @Test
    public void shouldMapWritesAndPreserveDocumentFields() {
        FakeClient client = new FakeClient();
        QCloudVectorStore store = new QCloudVectorStore(config(), client);
        Document document = document("doc-1", 0.1f);

        StoreResult stored = store.store(document, StoreOptions.ofCollectionName("tenant_docs"));
        assertTrue(stored.toString(), stored.isSuccess());
        assertEquals("tenant_docs", client.collection);
        com.tencent.tcvectordb.model.Document sdkDocument =
            (com.tencent.tcvectordb.model.Document) client.insertParam.getDocuments().get(0);
        assertEquals("doc-1", sdkDocument.getId());
        assertEquals("guide", sdkDocument.getObject("category"));
        assertEquals("正文-doc-1", sdkDocument.getObject(QCloudVectorStore.FIELD_CONTENT));
        assertEquals("标题-doc-1", sdkDocument.getObject(QCloudVectorStore.FIELD_TITLE));

        document.setVector(null);
        StoreResult updated = store.update(document, StoreOptions.ofCollectionName("tenant_docs"));
        assertTrue(updated.toString(), updated.isSuccess());
        assertEquals(Collections.singletonList("doc-1"), client.updateParam.getDocumentIds());
        assertNull(client.updateDocument.getId());
        assertNull(client.updateDocument.getVector());

        StoreResult deleted = store.delete(Arrays.asList("doc-1", 2L),
            StoreOptions.ofCollectionName("tenant_docs"));
        assertTrue(deleted.toString(), deleted.isSuccess());
        assertEquals(Arrays.asList("doc-1", "2"), client.deleteParam.getDocumentIds());
    }

    @Test
    public void shouldKeepCollectionsIsolatedAcrossCalls() {
        FakeClient client = new FakeClient();
        QCloudVectorStore store = new QCloudVectorStore(config(), client);

        assertTrue(store.store(document("a", 0.1f),
            StoreOptions.ofCollectionName("collection_a")).isSuccess());
        assertTrue(store.store(document("b", 0.2f),
            StoreOptions.ofCollectionName("collection_b")).isSuccess());

        assertEquals(Arrays.asList("collection_a", "collection_b"), client.collections);
    }

    @Test
    public void shouldMapVectorSearchAndRestoreResult() {
        FakeClient client = new FakeClient();
        client.searchResult = Collections.singletonList(Arrays.asList(
            sdkDocument("doc-1", 0.92), sdkDocument("doc-2", 0.35)));
        QCloudVectorStore store = new QCloudVectorStore(config(), client);

        SearchWrapper wrapper = new SearchWrapper()
            .condition("tenant = 'a' AND status NOT IN ('deleted', 'blocked')")
            .maxResults(8)
            .minScore(0.8)
            .outputFields("category")
            .outputVector(true);
        wrapper.setVector(new float[]{0.1f, 0.2f});
        List<Document> result = store.search(wrapper,
            StoreOptions.ofCollectionName("search_docs"));

        assertEquals("search_docs", client.collection);
        assertEquals(8, client.searchParam.getLimit());
        assertTrue(client.searchParam.isRetrieveVector());
        assertEquals("tenant = \"a\" and status not in (\"deleted\",\"blocked\")",
            client.searchParam.getFilter());
        assertEquals(Arrays.asList("category", QCloudVectorStore.FIELD_CONTENT,
            QCloudVectorStore.FIELD_TITLE), client.searchParam.getOutputFields());
        assertEquals(0.1f,
            client.searchParam.getVectors().get(0).get(0).floatValue(), 0.0001f);

        assertEquals(1, result.size());
        Document actual = result.get(0);
        assertEquals("doc-1", actual.getId());
        assertEquals("正文-doc-1", actual.getContent());
        assertEquals("标题-doc-1", actual.getTitle());
        assertEquals("guide", actual.getMetadata("category"));
        assertNull(actual.getMetadata(QCloudVectorStore.FIELD_CONTENT));
        assertEquals(0.92f, actual.getScore(), 0.0001f);
        assertArrayEquals(new float[]{0.3f, 0.4f}, actual.getVector(), 0.0001f);
    }

    @Test
    public void shouldUseSdkQueryForFilterOnlySearch() {
        FakeClient client = new FakeClient();
        client.queryResult = Collections.singletonList(sdkDocument("doc-1", null));
        QCloudVectorStore store = new QCloudVectorStore(config(), client);

        SearchWrapper wrapper = new SearchWrapper()
            .withVector(false)
            .eq("tenant", "a")
            .maxResults(20)
            .outputVector(true);
        List<Document> result = store.search(wrapper);

        assertEquals(1, result.size());
        assertNull(client.searchParam);
        assertEquals("tenant = \"a\"", client.queryParam.getFilter());
        assertEquals(20L, client.queryParam.getLimit());
        assertTrue(client.queryParam.isRetrieveVector());
    }

    @Test
    public void shouldRejectReservedFieldsPartitionsAndUnsupportedMetadata() {
        FakeClient client = new FakeClient();
        QCloudVectorStore store = new QCloudVectorStore(config(), client);
        Document document = document("doc-1", 0.1f);
        document.putMetadata(QCloudVectorStore.FIELD_CONTENT, "collision");
        StoreResult result = store.store(document);
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("reserved"));

        document = document("doc-2", 0.2f);
        document.putMetadata("enabled", true);
        result = store.store(document);
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Unsupported Tencent VectorDB metadata"));

        StoreOptions options = StoreOptions.ofCollectionName("docs").partitionName("p1");
        result = store.store(document("doc-3", 0.3f), options);
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("does not support"));
    }

    @Test
    public void shouldNormalizeSupportedMetadataTypes() {
        FakeClient client = new FakeClient();
        QCloudVectorStore store = new QCloudVectorStore(config(), client);
        Document document = document("doc-1", 0.1f);
        document.putMetadata("ratio", new BigDecimal("1.25"));
        document.putMetadata("small", (short) 3);
        document.putMetadata("tags", Arrays.asList("a", "b"));

        StoreResult result = store.store(document);
        assertTrue(result.toString(), result.isSuccess());
        com.tencent.tcvectordb.model.Document sdkDocument =
            (com.tencent.tcvectordb.model.Document) client.insertParam.getDocuments().get(0);
        assertTrue(sdkDocument.getObject("ratio") instanceof Double);
        assertTrue(sdkDocument.getObject("small") instanceof Integer);
        assertEquals(Arrays.asList("a", "b"), sdkDocument.getObject("tags"));
    }

    @Test
    public void shouldReportSdkFailuresAndInvalidQueries() {
        FakeClient client = new FakeClient();
        client.writeResponse = response(7, "permission denied", 0);
        client.writeResponse.setRequestId("request-7");
        QCloudVectorStore store = new QCloudVectorStore(config(), client);

        StoreResult result = store.store(document("doc-1", 0.1f));
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("request-7"));

        client.queryException = new RuntimeException("network failed");
        try {
            store.search(new SearchWrapper().withVector(false).eq("tenant", "a"));
            fail("Expected StoreException");
        } catch (StoreException expected) {
            assertTrue(expected.getMessage().contains("network failed"));
        }

        try {
            store.search(new SearchWrapper().withVector(false).maxResults(0));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxResults"));
        }
    }

    @Test
    public void shouldCloseInjectedClient() {
        FakeClient client = new FakeClient();
        QCloudVectorStore store = new QCloudVectorStore(config(), client);
        store.close();
        assertTrue(client.closed);
    }

    private QCloudVectorStoreConfig config() {
        QCloudVectorStoreConfig config = new QCloudVectorStoreConfig();
        config.setHost("http://127.0.0.1:8080");
        config.setAccount("root");
        config.setApiKey("test-key");
        config.setDatabase("test_db");
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

    private com.tencent.tcvectordb.model.Document sdkDocument(String id, Double score) {
        com.tencent.tcvectordb.model.Document.Builder builder =
            com.tencent.tcvectordb.model.Document.newBuilder()
                .withId(id)
                .withVector(Arrays.asList(0.3f, 0.4f))
                .addDocField(new DocField("category", "guide"))
                .addDocField(new DocField(QCloudVectorStore.FIELD_CONTENT, "正文-" + id))
                .addDocField(new DocField(QCloudVectorStore.FIELD_TITLE, "标题-" + id));
        if (score != null) {
            builder.withScore(score);
        }
        return builder.build();
    }

    private static AffectRes response(int code, String message, long affected) {
        return new AffectRes(code, message, null, affected);
    }

    private static class FakeClient implements QCloudVectorClient {
        private String collection;
        private final List<String> collections = new ArrayList<>();
        private InsertParam insertParam;
        private DeleteParam deleteParam;
        private UpdateParam updateParam;
        private com.tencent.tcvectordb.model.Document updateDocument;
        private SearchByVectorParam searchParam;
        private QueryParam queryParam;
        private AffectRes writeResponse = response(0, "success", 1);
        private List<List<com.tencent.tcvectordb.model.Document>> searchResult = Collections.emptyList();
        private List<com.tencent.tcvectordb.model.Document> queryResult = Collections.emptyList();
        private RuntimeException queryException;
        private boolean closed;

        private void record(String collection) {
            this.collection = collection;
            this.collections.add(collection);
        }

        @Override
        public AffectRes upsert(String database, String collection, InsertParam param) {
            record(collection);
            this.insertParam = param;
            return writeResponse;
        }

        @Override
        public AffectRes delete(String database, String collection, DeleteParam param) {
            record(collection);
            this.deleteParam = param;
            return writeResponse;
        }

        @Override
        public AffectRes update(String database, String collection, UpdateParam param,
                                com.tencent.tcvectordb.model.Document document) {
            record(collection);
            this.updateParam = param;
            this.updateDocument = document;
            return writeResponse;
        }

        @Override
        public List<List<com.tencent.tcvectordb.model.Document>> search(
            String database, String collection, SearchByVectorParam param) {
            record(collection);
            this.searchParam = param;
            return searchResult;
        }

        @Override
        public List<com.tencent.tcvectordb.model.Document> query(
            String database, String collection, QueryParam param) {
            record(collection);
            this.queryParam = param;
            if (queryException != null) {
                throw queryException;
            }
            return queryResult;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

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
import com.aliyun.dashvector.DashVectorClient;
import com.aliyun.dashvector.DashVectorClientConfig;
import com.aliyun.dashvector.models.requests.CreateCollectionRequest;
import com.aliyun.dashvector.models.responses.Response;
import com.aliyun.dashvector.proto.CollectionInfo;
import com.aliyun.dashvector.proto.FieldType;
import org.junit.Assume;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 使用显式环境变量连接真实 DashVector 测试 Collection。 */
public class AliyunVectorStoreIntegrationTest {

    @Test
    public void shouldRoundTripAgainstDashVector() {
        String enabled = System.getenv("DASHVECTOR_INTEGRATION_TEST");
        String endpoint = System.getenv("DASHVECTOR_ENDPOINT");
        String apiKey = System.getenv("DASHVECTOR_API_KEY");
        Assume.assumeTrue("true".equalsIgnoreCase(enabled));
        Assume.assumeNotNull(endpoint, apiKey);

        int dimension = Integer.parseInt(
            System.getenv().getOrDefault("DASHVECTOR_DIMENSION", "4"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String configuredCollection = System.getenv("DASHVECTOR_COLLECTION");
        boolean createCollections = configuredCollection == null || configuredCollection.trim().isEmpty();
        String collection = createCollections ? "af_it_a_" + suffix : configuredCollection;
        String isolatedCollection = createCollections ? "af_it_b_" + suffix : null;

        float[] vector = new float[dimension];
        vector[0] = 1.0f;

        AliyunVectorStoreConfig config = new AliyunVectorStoreConfig();
        config.setEndpoint(endpoint);
        config.setApiKey(apiKey);
        config.setDefaultCollectionName(collection);

        DashVectorClient admin = new DashVectorClient(DashVectorClientConfig.builder()
            .endpoint(endpoint)
            .apiKey(apiKey)
            .timeout(30.0f)
            .build());
        boolean collectionCreated = false;
        boolean isolatedCollectionCreated = false;
        String runId = "agents-flex-" + UUID.randomUUID();
        String documentId = runId + "-doc";
        boolean documentStored = false;
        try {
            if (createCollections) {
                createCollection(admin, collection, dimension);
                collectionCreated = true;
                createCollection(admin, isolatedCollection, dimension);
                isolatedCollectionCreated = true;
            }

            try (AliyunVectorStore store = new AliyunVectorStore(config)) {
                Document document = Document.of("DashVector SDK integration test");
                document.setId(documentId);
                document.setTitle("integration-test");
                document.setVector(vector);
                document.putMetadata("agentsflex_test_run", runId);

                StoreResult stored = store.store(document);
                assertTrue(stored.toString(), stored.isSuccess());
                documentStored = true;

                SearchWrapper query = new SearchWrapper()
                    .eq("agentsflex_test_run", runId)
                    .maxResults(10)
                    .outputVector(true);
                query.setVector(vector);
                List<Document> found = store.search(query);
                assertFalse(found.isEmpty());
                Document actual = find(found, documentId);
                assertEquals(document.getContent(), actual.getContent());
                assertEquals(document.getTitle(), actual.getTitle());

                if (isolatedCollection != null) {
                    List<Document> isolated = store.search(
                        query, StoreOptions.ofCollectionName(isolatedCollection));
                    assertTrue("Data leaked across collections", isolated.isEmpty());
                }

                document.setContent("DashVector SDK integration test updated");
                StoreResult updated = store.update(document);
                assertTrue(updated.toString(), updated.isSuccess());
                assertEquals(document.getContent(), find(store.search(query), documentId).getContent());

                StoreResult deleted = store.delete(Collections.singletonList(documentId));
                assertTrue(deleted.toString(), deleted.isSuccess());
                documentStored = false;
                assertTrue(store.search(query).isEmpty());
            }
        } finally {
            if (documentStored && !collectionCreated) {
                try (AliyunVectorStore cleanupStore = new AliyunVectorStore(config)) {
                    StoreResult deleted = cleanupStore.delete(Collections.singletonList(documentId));
                    assertTrue(deleted.toString(), deleted.isSuccess());
                }
            }
            try {
                if (isolatedCollectionCreated) {
                    requireSuccess("delete isolated integration collection",
                        admin.delete(isolatedCollection));
                }
            } finally {
                if (collectionCreated) {
                    requireSuccess("delete integration collection", admin.delete(collection));
                }
                admin.close();
            }
        }
    }

    private Document find(List<Document> documents, String documentId) {
        return documents.stream()
            .filter(item -> documentId.equals(item.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Stored document was not returned"));
    }

    private void createCollection(DashVectorClient client, String name, int dimension) {
        CreateCollectionRequest request = CreateCollectionRequest.builder()
            .name(name)
            .dimension(dimension)
            .metric(CollectionInfo.Metric.cosine)
            .filedSchema("agentsflex_test_run", FieldType.STRING)
            .filedSchema(AliyunVectorStore.FIELD_CONTENT, FieldType.STRING)
            .filedSchema(AliyunVectorStore.FIELD_TITLE, FieldType.STRING)
            .timeout(60)
            .build();
        requireSuccess("create integration collection", client.create(request));
    }

    private void requireSuccess(String operation, Response<?> response) {
        assertTrue(operation + " failed: " + response,
            response != null && Boolean.TRUE.equals(response.isSuccess()));
    }
}

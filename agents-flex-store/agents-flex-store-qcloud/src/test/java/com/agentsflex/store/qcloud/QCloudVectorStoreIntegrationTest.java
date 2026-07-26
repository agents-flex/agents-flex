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
import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.param.collection.CreateCollectionParam;
import com.tencent.tcvectordb.model.param.collection.FieldType;
import com.tencent.tcvectordb.model.param.collection.FilterIndex;
import com.tencent.tcvectordb.model.param.collection.HNSWParams;
import com.tencent.tcvectordb.model.param.collection.IndexType;
import com.tencent.tcvectordb.model.param.collection.MetricType;
import com.tencent.tcvectordb.model.param.collection.VectorIndex;
import com.tencent.tcvectordb.model.param.database.ConnectParam;
import com.tencent.tcvectordb.model.param.enums.ReadConsistencyEnum;
import org.junit.Assume;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 使用显式环境变量创建并清理真实腾讯云 VectorDB 测试数据。 */
public class QCloudVectorStoreIntegrationTest {

    @Test
    public void shouldRoundTripConditionsAndKeepCollectionsIsolated() throws Exception {
        String enabled = System.getenv("QCLOUD_VECTOR_INTEGRATION_TEST");
        String host = System.getenv("QCLOUD_VECTOR_HOST");
        String apiKey = System.getenv("QCLOUD_VECTOR_API_KEY");
        Assume.assumeTrue("true".equalsIgnoreCase(enabled));
        Assume.assumeNotNull(host, apiKey);

        String account = System.getenv().getOrDefault("QCLOUD_VECTOR_ACCOUNT", "root");
        int dimension = Integer.parseInt(
            System.getenv().getOrDefault("QCLOUD_VECTOR_DIMENSION", "4"));
        int replicaNum = Integer.parseInt(
            System.getenv().getOrDefault("QCLOUD_VECTOR_REPLICA_NUM", "0"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String database = "af_it_" + suffix;
        String collectionA = "docs_a_" + suffix;
        String collectionB = "docs_b_" + suffix;

        ConnectParam connection = ConnectParam.newBuilder()
            .withUrl(host)
            .withUsername(account)
            .withKey(apiKey)
            .withTimeout(30)
            .withConnectTimeout(10)
            .build();
        VectorDBClient admin = new VectorDBClient(
            connection, ReadConsistencyEnum.EVENTUAL_CONSISTENCY);
        boolean databaseCreated = false;
        boolean collectionACreated = false;
        boolean collectionBCreated = false;
        try {
            admin.createDatabase(database);
            databaseCreated = true;
            admin.createCollection(database, collection(collectionA, dimension, replicaNum));
            collectionACreated = true;
            admin.createCollection(database, collection(collectionB, dimension, replicaNum));
            collectionBCreated = true;

            QCloudVectorStoreConfig config = new QCloudVectorStoreConfig();
            config.setHost(host);
            config.setAccount(account);
            config.setApiKey(apiKey);
            config.setDatabase(database);
            config.setDefaultCollectionName(collectionA);
            config.setTimeout(30);

            float[] vector = new float[dimension];
            vector[0] = 1.0f;
            String documentId = "doc_" + suffix;
            Document document = Document.of("Tencent VectorDB integration test");
            document.setId(documentId);
            document.setTitle("integration-test");
            document.setVector(vector);
            document.putMetadata("tenant", "tenant-a");
            document.putMetadata("status", "published");
            document.putMetadata("year", 2026L);

            try (QCloudVectorStore store = new QCloudVectorStore(config)) {
                StoreResult stored = store.store(
                    document, StoreOptions.ofCollectionName(collectionA));
                assertTrue(stored.toString(), stored.isSuccess());

                SearchWrapper condition = new SearchWrapper()
                    .withVector(false)
                    .condition("tenant IN ('tenant-a', 'tenant-b') "
                        + "AND status NOT IN ('deleted', 'blocked') AND year BETWEEN 2025 AND 2027")
                    .maxResults(10)
                    .outputVector(true);
                List<Document> found = awaitDocuments(
                    store, condition, StoreOptions.ofCollectionName(collectionA), 20);
                Document actual = found.stream()
                    .filter(item -> documentId.equals(item.getId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Stored document was not returned"));
                assertEquals(document.getContent(), actual.getContent());
                assertEquals(document.getTitle(), actual.getTitle());
                assertEquals("tenant-a", actual.getMetadata("tenant"));
                assertTrue(actual.getVector() != null && actual.getVector().length == dimension);

                List<Document> otherCollection = store.search(
                    condition, StoreOptions.ofCollectionName(collectionB));
                assertTrue("Data leaked across collections", otherCollection.isEmpty());

                document.setContent("Tencent VectorDB integration test updated");
                StoreResult updated = store.update(
                    document, StoreOptions.ofCollectionName(collectionA));
                assertTrue(updated.toString(), updated.isSuccess());
                List<Document> updatedDocs = awaitDocuments(
                    store, condition, StoreOptions.ofCollectionName(collectionA), 20);
                assertEquals("Tencent VectorDB integration test updated",
                    updatedDocs.get(0).getContent());

                StoreResult deleted = store.delete(
                    Collections.singletonList(documentId),
                    StoreOptions.ofCollectionName(collectionA));
                assertTrue(deleted.toString(), deleted.isSuccess());
                assertTrue(awaitAbsent(
                    store, condition, StoreOptions.ofCollectionName(collectionA), 20));
            }
        } finally {
            try {
                if (collectionBCreated) {
                    admin.dropCollection(database, collectionB);
                }
            } finally {
                try {
                    if (collectionACreated) {
                        admin.dropCollection(database, collectionA);
                    }
                } finally {
                    try {
                        if (databaseCreated) {
                            admin.dropDatabase(database);
                        }
                    } finally {
                        admin.close();
                    }
                }
            }
        }
    }

    private CreateCollectionParam collection(String name, int dimension, int replicaNum) {
        return CreateCollectionParam.newBuilder()
            .withName(name)
            .withShardNum(1)
            .withReplicaNum(replicaNum)
            .withDescription("Agents-Flex integration test")
            .addField(new FilterIndex("id", FieldType.String, IndexType.PRIMARY_KEY))
            .addField(new VectorIndex("vector", dimension, IndexType.HNSW,
                MetricType.COSINE, new HNSWParams(16, 200)))
            .addField(new FilterIndex("tenant", FieldType.String, IndexType.FILTER))
            .addField(new FilterIndex("status", FieldType.String, IndexType.FILTER))
            .addField(new FilterIndex("year", FieldType.Uint64, IndexType.FILTER))
            .build();
    }

    private List<Document> awaitDocuments(
        QCloudVectorStore store,
        SearchWrapper wrapper,
        StoreOptions options,
        int attempts
    ) throws InterruptedException {
        List<Document> result = Collections.emptyList();
        for (int i = 0; i < attempts; i++) {
            result = store.search(wrapper, options);
            if (!result.isEmpty()) {
                return result;
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        return result;
    }

    private boolean awaitAbsent(
        QCloudVectorStore store,
        SearchWrapper wrapper,
        StoreOptions options,
        int attempts
    ) throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            if (store.search(wrapper, options).isEmpty()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        return false;
    }
}

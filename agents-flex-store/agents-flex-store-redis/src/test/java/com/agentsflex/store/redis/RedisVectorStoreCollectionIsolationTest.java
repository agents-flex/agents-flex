/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.store.redis;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.VectorData;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RedisVectorStoreCollectionIsolationTest {

    private RedisVectorStore store;
    private String collectionA;
    private String collectionB;
    private final List<String> documentKeys = new ArrayList<>();

    @Before
    public void setUp() {
        String testId = UUID.randomUUID().toString();
        RedisVectorStoreConfig config = new RedisVectorStoreConfig();
        config.setUri(System.getProperty("redis.test.uri", "redis://127.0.0.1:6379"));
        config.setStorePrefix("agents-flex-it:" + testId + ":");

        store = new RedisVectorStore(config);
        store.setEmbeddingModel(new FixedEmbeddingModel());

        try {
            store.jedis.ping();
            store.jedis.ftList();
        } catch (RuntimeException e) {
            store.jedis.close();
            store = null;
            Assume.assumeNoException("Redis with the RediSearch module is required", e);
        }
    }

    @After
    public void tearDown() {
        if (store == null) {
            return;
        }
        dropIndex(collectionA);
        dropIndex(collectionB);
        for (String documentKey : documentKeys) {
            store.jedis.del(documentKey);
        }
        store.jedis.close();
    }

    @Test
    public void shouldIsolateDifferentCollectionNames() {
        String suffix = UUID.randomUUID().toString();
        collectionA = "collection-a-" + suffix;
        collectionB = "collection-b-" + suffix;

        storeDocument(collectionA, "doc-a", "content-a");
        storeDocument(collectionB, "doc-b", "content-b");

        assertSingleDocument(collectionA, "doc-a", "content-a");
        assertSingleDocument(collectionB, "doc-b", "content-b");
    }

    @Test
    @Ignore("Known prefix-overlap issue; not part of the current RedisVectorStore fixes")
    public void shouldIsolateCollectionNamesWithPrefixRelationship() {
        String base = "collection-" + UUID.randomUUID();
        collectionA = base;
        collectionB = base + ":child";

        storeDocument(collectionA, "doc-a", "content-a");
        storeDocument(collectionB, "doc-b", "content-b");

        assertSingleDocument(collectionA, "doc-a", "content-a");
        assertSingleDocument(collectionB, "doc-b", "content-b");
    }

    @Test
    public void shouldApplyConditionMinScoreAndOutputVectorOptions() {
        collectionA = "collection-options-" + UUID.randomUUID();

        Document docA = document("doc-a", "content-a", new float[]{1.0f, 0.0f});
        docA.putMetadata("category", "AI");
        docA.putMetadata("views", 10);
        Document docB = document("doc-b", "content-b", new float[]{0.8f, 0.6f});
        docB.putMetadata("category", "ML");
        docB.putMetadata("views", 20);
        Document docC = document("doc-c", "content-c", new float[]{-1.0f, 0.0f});
        docC.putMetadata("category", "AI");
        docC.putMetadata("views", 30);

        storeDocuments(collectionA, Arrays.asList(docA, docB, docC));

        SearchWrapper search = new SearchWrapper();
        search.setVector(new float[]{1.0f, 0.0f});
        search.setMaxResults(10);
        search.setMinScore(0.75d);
        search.setOutputVector(false);
        search.outputFields("category", "views");
        search.eq("category", "AI").ge("views", 10);

        List<Document> documents = store.search(search, StoreOptions.ofCollectionName(collectionA));

        assertEquals(1, documents.size());
        assertEquals("doc-a", documents.get(0).getId());
        assertNull(documents.get(0).getVector());
        assertEquals("AI", documents.get(0).getMetadata("category"));
        assertEquals("10", documents.get(0).getMetadata("views"));
        assertTrue(documents.get(0).getScore() >= 0.75f);
    }

    @Test
    public void shouldApplyUnaryNotAndRejectUnsupportedNullPredicates() {
        collectionA = "collection-not-" + UUID.randomUUID();
        Document ai = document("doc-ai", "content-ai", new float[]{1.0f, 0.0f});
        ai.putMetadata("category", "AI");
        Document ml = document("doc-ml", "content-ml", new float[]{1.0f, 0.0f});
        ml.putMetadata("category", "ML");
        storeDocuments(collectionA, Arrays.asList(ai, ml));

        SearchWrapper negated = search().not(group -> group.eq("category", "AI"));
        List<Document> documents = store.search(
            negated, StoreOptions.ofCollectionName(collectionA));

        assertEquals(1, documents.size());
        assertEquals("doc-ml", documents.get(0).getId());

        try {
            store.search(search().isNull("optional"), StoreOptions.ofCollectionName(collectionA));
            fail("Expected unsupported null predicate");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("IS NULL"));
        }
    }

    @Test
    public void shouldRejectMetadataThatUsesReservedFields() {
        collectionA = "collection-reserved-" + UUID.randomUUID();
        Document document = document("doc-a", "content-a", new float[]{1.0f, 0.0f});
        document.putMetadata("vector", "not-a-vector");

        try {
            store.store(Collections.singletonList(document), StoreOptions.ofCollectionName(collectionA));
            fail("Expected reserved metadata field to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reserved"));
        }
    }

    @Test
    public void shouldRejectAnExistingIndexWithDifferentPrefix() {
        collectionA = "collection-schema-" + UUID.randomUUID();
        storeDocument(collectionA, "doc-a", "content-a");

        RedisVectorStoreConfig otherConfig = new RedisVectorStoreConfig();
        otherConfig.setUri(System.getProperty("redis.test.uri", "redis://127.0.0.1:6379"));
        otherConfig.setStorePrefix("another-prefix:" + UUID.randomUUID() + ":");
        RedisVectorStore otherStore = new RedisVectorStore(otherConfig);
        otherStore.setEmbeddingModel(new FixedEmbeddingModel());
        try {
            otherStore.search(search(), StoreOptions.ofCollectionName(collectionA));
            fail("Expected incompatible index prefix to be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("incompatible prefixes"));
        } finally {
            otherStore.jedis.close();
        }
    }

    @Test
    public void shouldRejectAnExistingIndexWithDifferentVectorDimensions() {
        collectionA = "collection-dimensions-" + UUID.randomUUID();
        storeDocument(collectionA, "doc-a", "content-a");

        RedisVectorStore otherStore = newStore(store.config.getStorePrefix());
        otherStore.setEmbeddingModel(new EmbeddingModel() {
            @Override
            public VectorData embed(Document document, EmbeddingOptions options) {
                VectorData vectorData = new VectorData();
                vectorData.setVector(new float[]{1.0f, 0.0f, 0.0f});
                return vectorData;
            }
        });
        SearchWrapper search = new SearchWrapper();
        search.setVector(new float[]{1.0f, 0.0f, 0.0f});
        try {
            otherStore.search(search, StoreOptions.ofCollectionName(collectionA));
            fail("Expected incompatible vector dimensions to be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("incompatible vector field"));
        } finally {
            otherStore.jedis.close();
        }
    }

    @Test
    public void shouldCreateTheSameIndexConcurrentlyAcrossStoreInstances() throws Exception {
        collectionA = "collection-concurrent-" + UUID.randomUUID();
        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<RedisVectorStore> stores = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < concurrency; i++) {
                RedisVectorStore concurrentStore = newStore(store.config.getStorePrefix());
                stores.add(concurrentStore);
                final int documentNumber = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    Document document = document("doc-" + documentNumber, "content-" + documentNumber,
                        new float[]{1.0f, 0.0f});
                    concurrentStore.store(document, StoreOptions.ofCollectionName(collectionA));
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }

            for (int i = 0; i < concurrency; i++) {
                documentKeys.add(store.getPrefix(collectionA) + "doc-" + i);
            }
            SearchWrapper search = search();
            search.setMaxResults(concurrency);
            assertEquals(concurrency,
                store.search(search, StoreOptions.ofCollectionName(collectionA)).size());
        } finally {
            executor.shutdownNow();
            for (RedisVectorStore concurrentStore : stores) {
                concurrentStore.jedis.close();
            }
        }
    }

    private void storeDocument(String collectionName, String id, String content) {
        storeDocuments(collectionName, Collections.singletonList(
            document(id, content, new float[]{1.0f, 0.0f})
        ));
    }

    private void storeDocuments(String collectionName, List<Document> documents) {
        store.store(documents, StoreOptions.ofCollectionName(collectionName));
        for (Document document : documents) {
            documentKeys.add(store.getPrefix(collectionName) + document.getId());
        }
    }

    private Document document(String id, String content, float[] vector) {
        Document document = Document.of(content);
        document.setId(id);
        document.setVector(vector);
        return document;
    }

    private SearchWrapper search() {
        SearchWrapper search = new SearchWrapper();
        search.setVector(new float[]{1.0f, 0.0f});
        search.setMaxResults(10);
        return search;
    }

    private RedisVectorStore newStore(String storePrefix) {
        RedisVectorStoreConfig config = new RedisVectorStoreConfig();
        config.setUri(System.getProperty("redis.test.uri", "redis://127.0.0.1:6379"));
        config.setStorePrefix(storePrefix);
        RedisVectorStore newStore = new RedisVectorStore(config);
        newStore.setEmbeddingModel(new FixedEmbeddingModel());
        return newStore;
    }

    private void assertSingleDocument(String collectionName, String expectedId, String expectedContent) {
        List<Document> documents = store.search(search(), StoreOptions.ofCollectionName(collectionName));

        assertEquals("Unexpected documents in collection " + collectionName, 1, documents.size());
        assertEquals(expectedId, documents.get(0).getId());
        assertEquals(expectedContent, documents.get(0).getContent());
    }

    private void dropIndex(String collectionName) {
        if (collectionName != null && store.getIndexInfo(collectionName) != null) {
            store.jedis.ftDropIndex(collectionName);
        }
    }

    private static class FixedEmbeddingModel implements EmbeddingModel {
        @Override
        public VectorData embed(Document document, EmbeddingOptions options) {
            VectorData vectorData = new VectorData();
            vectorData.setVector(new float[]{1.0f, 0.0f});
            return vectorData;
        }
    }
}

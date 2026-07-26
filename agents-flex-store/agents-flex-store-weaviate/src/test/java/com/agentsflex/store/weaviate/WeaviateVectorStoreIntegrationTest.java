/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.weaviate;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.v1.schema.model.DataType;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 真实 Weaviate 集成测试。
 *
 * <p>测试连接禁用了 auto-schema 和外部 vectorizer 的 Weaviate Docker，实际创建 HNSW Collection、
 * 写入向量并执行 nearVector 与 where 查询。每次测试使用随机 Collection，结束后自动删除。</p>
 */
public class WeaviateVectorStoreIntegrationTest {

    private WeaviateClient cleanupClient;
    private WeaviateVectorStore store;
    private String firstCollection;
    private String secondCollection;

    @Before
    public void setUp() {
        Assume.assumeTrue("Enable with -Dagentsflex.weaviate.integration=true",
            Boolean.getBoolean("agentsflex.weaviate.integration"));
        String serverUrl = System.getProperty("agentsflex.weaviate.url", "http://127.0.0.1:8082");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        firstCollection = "AgentsFlexItA" + suffix;
        secondCollection = "AgentsFlexItB" + suffix;

        WeaviateVectorStoreConfig config = new WeaviateVectorStoreConfig();
        config.setServerUrl(serverUrl);
        config.setDefaultCollectionName(firstCollection);
        config.setVectorDimension(3);
        Map<String, WeaviateMetadataType> types = new LinkedHashMap<>();
        types.put("optional", WeaviateMetadataType.TEXT);
        types.put("tags", WeaviateMetadataType.TEXT_ARRAY);
        config.setMetadataFieldTypes(types);
        store = new WeaviateVectorStore(config);
        cleanupClient = client(serverUrl);
    }

    @After
    public void tearDown() {
        deleteCollection(firstCollection);
        deleteCollection(secondCollection);
    }

    @Test
    public void shouldKeepCollectionsIsolatedAndSearchRealHnswIndex() {
        StoreOptions first = StoreOptions.ofCollectionName(firstCollection);
        StoreOptions second = StoreOptions.ofCollectionName(secondCollection);
        assertTrue(store.doStore(Collections.singletonList(
            document("same-id", "first", "AI", "ready", 10, new float[]{1, 0, 0})), first).isSuccess());
        assertTrue(store.doStore(Collections.singletonList(
            document("same-id", "second", "Java", "ready", 20, new float[]{1, 0, 0})), second).isSuccess());

        List<Document> firstResult = store.doSearch(vectorSearch(), first);
        List<Document> secondResult = store.doSearch(vectorSearch(), second);

        assertEquals(1, firstResult.size());
        assertEquals("same-id", firstResult.get(0).getId());
        assertEquals("first", firstResult.get(0).getContent());
        assertEquals(1, secondResult.size());
        assertEquals("second", secondResult.get(0).getContent());
        assertNotNull(firstResult.get(0).getScore());

        WeaviateClass schema = cleanupClient.schema().classGetter()
            .withClassName(firstCollection).run().getResult();
        assertEquals("none", schema.getVectorizer());
        assertEquals("cosine", schema.getVectorIndexConfig().getDistance());
        assertTrue(schema.getInvertedIndexConfig().getIndexNullState());
        assertTrue(schema.getProperties().stream().anyMatch(property ->
            "metadata_category".equals(property.getName())
                && DataType.TEXT.equals(property.getDataType().get(0))));
    }

    @Test
    public void shouldApplyComplexFilterAndSqlConditionOnServer() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        assertTrue(store.doStore(Arrays.asList(
            document("doc-1", "AI document", "AI", "ready", 10, new float[]{1, 0, 0}),
            document("doc-2", "Java document", "Java", "ready", 20, new float[]{0.9f, 0.1f, 0}),
            document("doc-3", "Ops document", "Ops", "deleted", 30, new float[]{0, 1, 0})
        ), options).isSuccess());

        SearchWrapper wrapper = vectorSearch()
            .in("category", Arrays.asList("AI", "Ops"))
            .between("views", 5, 15)
            .isNotNull("category")
            .not(group -> group.eq("status", "deleted"))
            .outputFields("category", "views")
            .outputVector(true)
            .minScore(0.5);
        List<Document> results = store.doSearch(wrapper, options);

        assertEquals(1, results.size());
        assertEquals("doc-1", results.get(0).getId());
        assertEquals("AI", results.get(0).getMetadata("category"));
        assertFalse(results.get(0).containsMetadata("status"));
        assertArrayEquals(new float[]{1, 0, 0}, results.get(0).getVector(), 0.0001f);

        List<Document> filterOnly = store.doSearch(new SearchWrapper()
            .withVector(false)
            .condition("views >= 20 AND category NOT IN ('AI')")
            .maxResults(10), options);
        assertEquals(2, filterOnly.size());
    }

    @Test
    public void shouldSupportNullOutputUpdateAndDelete() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        Document document = document("doc-1", "before", "AI", "ready", 10,
            new float[]{1, 0, 0});
        document.putMetadata("tags", Collections.emptyList());
        assertTrue(store.doStore(Collections.singletonList(document), options).isSuccess());

        Document filtered = store.doSearch(new SearchWrapper()
            .withVector(false)
            .isNull("optional")
            .outputFields("category", "tags")
            .maxResults(10), options).get(0);
        assertEquals("AI", filtered.getMetadata("category"));
        assertTrue(((List<?>) filtered.getMetadata("tags")).isEmpty());
        assertFalse(filtered.containsMetadata("views"));
        assertNull(filtered.getVector());
        assertNull(filtered.getScore());

        document.setContent("after");
        document.putMetadata("category", "Updated");
        assertTrue(store.doUpdate(Collections.singletonList(document), options).isSuccess());
        Document updated = store.doSearch(vectorSearch().eq("category", "Updated"), options).get(0);
        assertEquals("after", updated.getContent());

        StoreResult deleted = store.doDelete(Collections.singletonList("doc-1"), options);
        assertTrue(deleted.toString(), deleted.isSuccess());
        assertTrue(store.doSearch(new SearchWrapper().withVector(false).maxResults(10), options).isEmpty());
    }

    @Test
    public void shouldRejectMissingCollectionWhenAutomaticCreationIsDisabled() {
        WeaviateVectorStoreConfig config = new WeaviateVectorStoreConfig();
        config.setServerUrl(System.getProperty("agentsflex.weaviate.url", "http://127.0.0.1:8082"));
        config.setDefaultCollectionName(secondCollection);
        config.setAutoCreateCollection(false);
        WeaviateVectorStore withoutAutoCreate = new WeaviateVectorStore(config);

        StoreResult result = withoutAutoCreate.doStore(Collections.singletonList(
            document("doc", "content", "AI", "ready", 1, new float[]{1, 0, 0})), StoreOptions.DEFAULT);
        assertFalse(result.isSuccess());
    }

    @Test
    public void shouldSearchWithL2DistanceAndApplyMinScore() {
        WeaviateVectorStoreConfig config = new WeaviateVectorStoreConfig();
        config.setServerUrl(System.getProperty("agentsflex.weaviate.url", "http://127.0.0.1:8082"));
        config.setDefaultCollectionName(secondCollection);
        config.setVectorDimension(3);
        config.setSimilarity(WeaviateSimilarity.L2_SQUARED);
        WeaviateVectorStore l2Store = new WeaviateVectorStore(config);
        StoreOptions options = StoreOptions.ofCollectionName(secondCollection);

        assertTrue(l2Store.doStore(Arrays.asList(
            document("near", "near", "AI", "ready", 1, new float[]{1, 0, 0}),
            document("far", "far", "AI", "ready", 2, new float[]{0, 1, 0})
        ), options).isSuccess());
        List<Document> results = l2Store.doSearch(vectorSearch().minScore(0.75), options);

        assertEquals(1, results.size());
        assertEquals("near", results.get(0).getId());
        assertEquals(1.0f, results.get(0).getScore(), 0.0001f);
        assertEquals("l2-squared", cleanupClient.schema().classGetter()
            .withClassName(secondCollection).run().getResult().getVectorIndexConfig().getDistance());
    }

    private SearchWrapper vectorSearch() {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(new float[]{1, 0, 0});
        return wrapper;
    }

    private Document document(
        String id,
        String content,
        String category,
        String status,
        int views,
        float[] vector
    ) {
        Document document = Document.of(content);
        document.setId(id);
        document.setTitle("title-" + id);
        document.setVector(vector);
        document.putMetadata("category", category);
        document.putMetadata("status", status);
        document.putMetadata("views", views);
        return document;
    }

    private WeaviateClient client(String serverUrl) {
        URI uri = URI.create(serverUrl);
        String host = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        return new WeaviateClient(new Config(uri.getScheme(), host));
    }

    private void deleteCollection(String collectionName) {
        if (cleanupClient == null || collectionName == null) {
            return;
        }
        if (Boolean.TRUE.equals(cleanupClient.schema().exists().withClassName(collectionName).run().getResult())) {
            cleanupClient.schema().classDeleter().withClassName(collectionName).run();
        }
    }
}

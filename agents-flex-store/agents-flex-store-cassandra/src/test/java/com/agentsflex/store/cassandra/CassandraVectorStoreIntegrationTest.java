/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.cassandra;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
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
import static org.junit.Assert.fail;

/**
 * Cassandra 5.x 真实集成测试。
 *
 * <p>测试连接真实 Cassandra 节点，创建 vector<float, 3> 表与 SAI，实际执行 ANN、
 * 标量过滤、更新、删除和多表隔离。每个用例使用随机 keyspace，结束后完整删除。</p>
 */
public class CassandraVectorStoreIntegrationTest {

    private String contactPoint;
    private String localDatacenter;
    private String keyspace;
    private CassandraVectorStore store;

    @Before
    public void setUp() {
        Assume.assumeTrue("Enable with -Dagentsflex.cassandra.integration=true",
            Boolean.getBoolean("agentsflex.cassandra.integration"));
        contactPoint = System.getProperty("agentsflex.cassandra.contact-point", "127.0.0.1:9043");
        localDatacenter = System.getProperty("agentsflex.cassandra.local-datacenter", "datacenter1");
        keyspace = "af_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        store = new CassandraVectorStore(config("documents", CassandraSimilarity.COSINE));
    }

    @After
    public void tearDown() {
        if (store != null) {
            store.close();
        }
        if (keyspace != null) {
            try (CqlSession cleanup = session()) {
                cleanup.execute(com.datastax.oss.driver.api.core.cql.SimpleStatement.builder(
                    "DROP KEYSPACE IF EXISTS \"" + keyspace + "\"")
                    .setTimeout(Duration.ofSeconds(30)).build());
            }
        }
    }

    @Test
    public void shouldCreateSaiSearchFilterAndKeepCollectionsIsolated() {
        StoreOptions first = StoreOptions.ofCollectionName("documents");
        StoreOptions second = StoreOptions.ofCollectionName("documents_b");
        assertTrue(store.doStore(Arrays.asList(
            document("doc-1", "AI near", "AI", "ready", 10, new float[]{1, 0, 0}),
            document("doc-2", "Java near", "Java", "featured", 20, new float[]{0.9f, 0.1f, 0}),
            document("doc-3", "Ops far", "Ops", "deleted", 30, new float[]{0, 1, 0})
        ), first).isSuccess());
        assertTrue(store.doStore(Collections.singletonList(
            document("doc-1", "isolated", "Other", "ready", 1, new float[]{1, 0, 0})), second).isSuccess());

        SearchWrapper inAndRange = vectorSearch()
            .in("category", Arrays.asList("AI", "Ops"))
            .between("views", 5, 15)
            .outputFields("category", "views")
            .outputVector(true);
        List<Document> filtered = store.doSearch(inAndRange, first);
        assertEquals(1, filtered.size());
        assertEquals("doc-1", filtered.get(0).getId());
        assertEquals("AI", filtered.get(0).getMetadata("category"));
        assertFalse(filtered.get(0).containsMetadata("status"));
        assertArrayEquals(new float[]{1, 0, 0}, filtered.get(0).getVector(), 0.0001f);
        assertNotNull(filtered.get(0).getScore());

        List<Document> sqlOr = store.doSearch(new SearchWrapper()
            .withVector(false)
            .condition("category IN ('AI', 'Java') OR views >= 30")
            .maxResults(10), first);
        assertEquals(3, sqlOr.size());
        assertNull(sqlOr.get(0).getScore());
        assertNull(sqlOr.get(0).getVector());

        List<Document> isolated = store.doSearch(vectorSearch(), second);
        assertEquals(1, isolated.size());
        assertEquals("isolated", isolated.get(0).getContent());

        try (CqlSession verification = session()) {
            assertNotNull(verification.execute("SELECT index_name FROM system_schema.indexes"
                + " WHERE keyspace_name='" + keyspace + "' AND table_name='documents'").one());
            assertEquals("vector<float, 3>", verification.execute("SELECT type FROM system_schema.columns"
                + " WHERE keyspace_name='" + keyspace + "' AND table_name='documents'"
                + " AND column_name='embedding'").one().getString("type"));
        }
    }

    @Test
    public void shouldApplyMinScoreReplaceMetadataUpdateAndDelete() {
        StoreOptions options = StoreOptions.ofCollectionName("documents");
        Document near = document("near", "before", "AI", "ready", 10, new float[]{1, 0, 0});
        Document far = document("far", "far", "AI", "ready", 20, new float[]{0, 1, 0});
        assertTrue(store.doStore(Arrays.asList(near, far), options).isSuccess());

        List<Document> threshold = store.doSearch(vectorSearch().minScore(0.9), options);
        assertEquals(1, threshold.size());
        assertEquals("near", threshold.get(0).getId());

        near.setContent("after");
        near.setMetadataMap(Collections.singletonMap("category", "Updated"));
        assertTrue(store.doUpdate(Collections.singletonList(near), options).isSuccess());
        Document updated = store.doSearch(new SearchWrapper().withVector(false)
            .eq("category", "Updated").maxResults(10), options).get(0);
        assertEquals("after", updated.getContent());
        assertFalse(updated.containsMetadata("status"));
        assertFalse(updated.containsMetadata("views"));

        StoreResult deleted = store.doDelete(Arrays.asList("near", "far"), options);
        assertTrue(deleted.toString(), deleted.isSuccess());
        assertTrue(store.doSearch(new SearchWrapper().withVector(false).maxResults(10), options).isEmpty());
    }

    @Test
    public void shouldUseEuclideanIndexAndRejectUnsupportedConditions() {
        store.close();
        store = new CassandraVectorStore(config("euclidean_docs", CassandraSimilarity.EUCLIDEAN));
        StoreOptions options = StoreOptions.ofCollectionName("euclidean_docs");
        assertTrue(store.doStore(Arrays.asList(
            document("near", "near", "AI", "ready", 1, new float[]{1, 0, 0}),
            document("far", "far", "AI", "ready", 2, new float[]{0, 1, 0})
        ), options).isSuccess());
        assertEquals("near", store.doSearch(vectorSearch(), options).get(0).getId());

        assertUnsupported(new SearchWrapper().ne("status", "deleted"), options);
        assertUnsupported(new SearchWrapper().nin("category", Arrays.asList("AI")), options);
        assertUnsupported(new SearchWrapper().isNull("optional"), options);
        assertUnsupported(new SearchWrapper().not(group -> group.eq("status", "deleted")), options);
    }

    @Test
    public void shouldFailClearlyWhenCollectionCreationIsDisabled() {
        CassandraVectorStoreConfig disabled = config("missing_table", CassandraSimilarity.COSINE);
        disabled.setAutoCreateCollection(false);
        try (CassandraVectorStore withoutAutoCreate = new CassandraVectorStore(disabled)) {
            StoreResult result = withoutAutoCreate.doStore(Collections.singletonList(
                document("doc", "content", "AI", "ready", 1, new float[]{1, 0, 0})),
                StoreOptions.DEFAULT);
            assertFalse(result.isSuccess());
            assertNotNull(result.getException());
        }
    }

    private CassandraVectorStoreConfig config(String collection, CassandraSimilarity similarity) {
        CassandraVectorStoreConfig config = new CassandraVectorStoreConfig();
        config.setContactPoint(contactPoint);
        config.setLocalDatacenter(localDatacenter);
        config.setKeyspace(keyspace);
        config.setDefaultCollectionName(collection);
        config.setVectorDimension(3);
        config.setSimilarity(similarity);
        Map<String, CassandraMetadataType> types = new LinkedHashMap<>();
        types.put("category", CassandraMetadataType.TEXT);
        types.put("status", CassandraMetadataType.TEXT);
        types.put("views", CassandraMetadataType.INT);
        config.setMetadataFieldTypes(types);
        return config;
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

    private CqlSession session() {
        int separator = contactPoint.lastIndexOf(':');
        return CqlSession.builder()
            .addContactPoint(new InetSocketAddress(
                contactPoint.substring(0, separator),
                Integer.parseInt(contactPoint.substring(separator + 1))))
            .withLocalDatacenter(localDatacenter)
            .build();
    }

    private void assertUnsupported(SearchWrapper wrapper, StoreOptions options) {
        try {
            store.doSearch(wrapper.withVector(false), options);
            fail("Expected unsupported Cassandra condition");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains("Cassandra 5.x"));
        }
    }
}

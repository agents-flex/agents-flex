/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.clickhouse;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 在真实 ClickHouse 25.8+、MergeTree 表和 HNSW 索引上执行的集成测试。 */
public class ClickHouseVectorStoreIntegrationTest {
    private String host;
    private int port;
    private String username;
    private String password;
    private String database;
    private String firstCollection;
    private String secondCollection;
    private ClickHouseVectorStore store;

    @Before
    public void setUp() {
        Assume.assumeTrue("Enable with -Dagentsflex.clickhouse.integration=true",
            Boolean.getBoolean("agentsflex.clickhouse.integration"));
        host = System.getProperty("agentsflex.clickhouse.host", "127.0.0.1");
        port = Integer.getInteger("agentsflex.clickhouse.port", 8124);
        username = System.getProperty("agentsflex.clickhouse.username", "agentsflex");
        password = System.getProperty("agentsflex.clickhouse.password", "agentsflex");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        database = "af_it_" + suffix;
        firstCollection = "docs_a";
        secondCollection = "docs_b";
        store = new ClickHouseVectorStore(config(firstCollection, ClickHouseSimilarity.COSINE));
    }

    @After
    public void tearDown() throws Exception {
        if (database == null) return;
        try (Connection connection = connection("default"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + database + "` SYNC");
        }
    }

    @Test
    public void shouldUseRealHnswAndKeepCollectionsIsolated() throws Exception {
        StoreOptions first = StoreOptions.ofCollectionName(firstCollection);
        StoreOptions second = StoreOptions.ofCollectionName(secondCollection);
        assertStoreSuccess(store.doStore(Arrays.asList(
            document("doc-1", "AI", "AI", "ready", 10, new float[]{1, 0, 0}),
            document("doc-2", "Java", "Java", "ready", 20, new float[]{0, 1, 0})
        ), first));
        assertStoreSuccess(store.doStore(Collections.singletonList(
            document("doc-1", "isolated", "Other", "ready", 1, new float[]{1, 0, 0})), second));

        Document firstResult = store.doSearch(vectorSearch(), first).get(0);
        Document secondResult = store.doSearch(vectorSearch(), second).get(0);
        assertEquals("AI", firstResult.getContent());
        assertEquals("isolated", secondResult.getContent());
        assertEquals(1.0f, firstResult.getScore(), 0.0002f);
        assertTrue(indexExists(firstCollection));
        assertTrue(explainVectorQuery(firstCollection).contains("vector_idx"));
    }

    private static void assertStoreSuccess(StoreResult result) {
        assertTrue(result.toString(), result.isSuccess());
    }

    @Test
    public void shouldExecuteComplexSqlNullAndFilterOnlyQueries() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        Document ops = document("doc-3", "Ops", "Ops", "deleted", 30, new float[]{0, 1, 0});
        ops.putMetadata("optional", "set");
        assertTrue(store.doStore(Arrays.asList(
            document("doc-1", "AI", "AI", "ready", 10, new float[]{1, 0, 0}),
            document("doc-2", "Java", "Java", "ready", 20, new float[]{0.9f, 0.1f, 0}),
            ops
        ), options).isSuccess());

        List<Document> vectorResult = store.doSearch(vectorSearch()
            .condition("category IN ('AI', 'Ops') AND views BETWEEN 5 AND 15 AND active = true")
            .isNull("optional")
            .not(group -> group.eq("status", "deleted"))
            .outputFields("category", "views")
            .outputVector(true)
            .minScore(0.5), options);
        assertEquals(1, vectorResult.size());
        assertEquals("doc-1", vectorResult.get(0).getId());
        assertEquals("AI", vectorResult.get(0).getMetadata("category"));
        assertFalse(vectorResult.get(0).containsMetadata("status"));
        assertArrayEquals(new float[]{1, 0, 0}, vectorResult.get(0).getVector(), 0.0001f);

        List<Document> filterOnly = store.doSearch(new SearchWrapper().withVector(false)
            .condition("views >= 20 AND category NOT IN ('AI')")
            .outputFields("category", "views").maxResults(10), options);
        assertEquals(2, filterOnly.size());
        assertNull(filterOnly.get(0).getScore());
        assertNull(filterOnly.get(0).getVector());

        List<Document> notNull = store.doSearch(new SearchWrapper().withVector(false)
            .isNotNull("optional").maxResults(10), options);
        assertEquals(1, notNull.size());
        assertEquals("doc-3", notNull.get(0).getId());
    }

    @Test
    public void shouldOverwriteWithoutDuplicateRowsThenDelete() throws Exception {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        Document document = document("same", "before", "AI", "ready", 10, new float[]{1, 0, 0});
        assertTrue(store.doStore(Collections.singletonList(document), options).isSuccess());
        document.setContent("after");
        document.putMetadata("rank", 99L);
        assertTrue(store.doUpdate(Collections.singletonList(document), options).isSuccess());

        assertEquals(1L, scalarLong("SELECT count() FROM `" + database + "`.`"
            + firstCollection + "` WHERE id = 'same'"));
        Document updated = store.doSearch(new SearchWrapper().withVector(false)
            .eq("id", "same").outputFields("rank").maxResults(10), options).get(0);
        assertEquals("after", updated.getContent());
        assertEquals(99L, ((Number) updated.getMetadata("rank")).longValue());

        StoreResult deleted = store.doDelete(Collections.singletonList("same"), options);
        assertTrue(deleted.toString(), deleted.isSuccess());
        assertTrue(store.doSearch(new SearchWrapper().withVector(false).maxResults(10), options).isEmpty());
    }

    @Test
    public void shouldSupportL2AndDotProduct() throws Exception {
        ClickHouseVectorStore l2Store = new ClickHouseVectorStore(config("docs_l2", ClickHouseSimilarity.L2));
        StoreOptions l2Options = StoreOptions.ofCollectionName("docs_l2");
        assertTrue(l2Store.doStore(Arrays.asList(
            document("near", "near", "AI", "ready", 1, new float[]{1, 0, 0}),
            document("far", "far", "AI", "ready", 2, new float[]{0, 1, 0})
        ), l2Options).isSuccess());
        List<Document> l2 = l2Store.doSearch(vectorSearch().minScore(0.75), l2Options);
        assertEquals(1, l2.size());
        assertEquals("near", l2.get(0).getId());
        assertEquals(1.0f, l2.get(0).getScore(), 0.0001f);

        ClickHouseVectorStore dotStore = new ClickHouseVectorStore(
            config("docs_dot", ClickHouseSimilarity.DOT_PRODUCT));
        StoreOptions dotOptions = StoreOptions.ofCollectionName("docs_dot");
        assertTrue(dotStore.doStore(Arrays.asList(
            document("best", "best", "AI", "ready", 1, new float[]{1, 0, 0}),
            document("other", "other", "AI", "ready", 2, new float[]{0, 1, 0})
        ), dotOptions).isSuccess());
        List<Document> dot = dotStore.doSearch(vectorSearch(), dotOptions);
        assertEquals("best", dot.get(0).getId());
        assertEquals(1.0f, dot.get(0).getScore(), 0.0001f);
        assertFalse(indexExists("docs_dot"));
    }

    @Test
    public void shouldRejectMissingCollectionAndWrongDimension() {
        ClickHouseVectorStoreConfig noAutoConfig = config("missing_table", ClickHouseSimilarity.COSINE);
        noAutoConfig.setAutoCreateCollection(false);
        ClickHouseVectorStore noAutoStore = new ClickHouseVectorStore(noAutoConfig);
        StoreResult missing = noAutoStore.doStore(Collections.singletonList(
            document("doc", "content", "AI", "ready", 1, new float[]{1, 0, 0})), StoreOptions.DEFAULT);
        assertFalse(missing.isSuccess());

        StoreResult wrongDimension = store.doStore(Collections.singletonList(
            document("bad", "bad", "AI", "ready", 1, new float[]{1, 0})), StoreOptions.DEFAULT);
        assertFalse(wrongDimension.isSuccess());
    }

    private ClickHouseVectorStoreConfig config(String collection, ClickHouseSimilarity similarity) {
        ClickHouseVectorStoreConfig config = new ClickHouseVectorStoreConfig();
        config.setHost(host);
        config.setPort(port);
        config.setUsername(username);
        config.setPassword(password);
        config.setDatabaseName(database);
        config.setDefaultCollectionName(collection);
        config.setVectorDimension(3);
        config.setSimilarity(similarity);
        config.setHnswM(16);
        config.setHnswEfConstruction(50);
        return config;
    }

    private SearchWrapper vectorSearch() {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(new float[]{1, 0, 0});
        return wrapper;
    }

    private Document document(Object id, String content, String category, String status, int views, float[] vector) {
        Document document = Document.of(content);
        document.setId(id);
        document.setTitle("title-" + id);
        document.setVector(vector);
        document.putMetadata("category", category);
        document.putMetadata("status", status);
        document.putMetadata("views", views);
        document.putMetadata("active", true);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("level", 2);
        document.putMetadata("profile", profile);
        return document;
    }

    private boolean indexExists(String collection) throws Exception {
        return scalarLong("SELECT count() FROM system.data_skipping_indices WHERE database = '"
            + database + "' AND table = '" + collection + "' AND name = 'vector_idx'") == 1;
    }

    private String explainVectorQuery(String collection) throws Exception {
        String sql = "EXPLAIN indexes = 1 WITH CAST('[1.0,0.0,0.0]' AS Array(Float32)) AS q "
            + "SELECT id FROM `" + database + "`.`" + collection
            + "` ORDER BY cosineDistance(vector, q) LIMIT 2";
        StringBuilder result = new StringBuilder();
        try (Connection connection = connection(database); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) result.append(resultSet.getString(1)).append('\n');
        }
        return result.toString();
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection connection = connection(database); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private Connection connection(String db) throws Exception {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        return DriverManager.getConnection("jdbc:clickhouse:http://" + host + ':' + port + '/' + db
            + "?jdbc_ignore_unsupported_values=true", properties);
    }
}

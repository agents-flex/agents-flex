/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.mariadb;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mariadb.jdbc.MariaDbDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MariaDBVectorStoreIntegrationTest {

    private MariaDBVectorStore store;
    private MariaDbDataSource dataSource;
    private String firstCollection;
    private String secondCollection;
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;

    @Before
    public void setUp() throws SQLException {
        Assume.assumeTrue("Enable with -Dagentsflex.mariadb.integration=true",
            Boolean.getBoolean("agentsflex.mariadb.integration"));

        host = System.getProperty("agentsflex.mariadb.host", "127.0.0.1");
        port = Integer.getInteger("agentsflex.mariadb.port", 3307);
        database = System.getProperty("agentsflex.mariadb.database", "agent_vector");
        username = System.getProperty("agentsflex.mariadb.username", "agentsflex");
        password = System.getProperty("agentsflex.mariadb.password", "agentsflex");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        firstCollection = "agents_flex_it_a_" + suffix;
        secondCollection = "agents_flex_it_b_" + suffix;

        store = new MariaDBVectorStore(config(firstCollection, MariaDBDistanceType.COSINE));

        dataSource = new MariaDbDataSource("jdbc:mariadb://" + host + ":" + port + "/" + database);
        dataSource.setUser(username);
        dataSource.setPassword(password);
    }

    @After
    public void tearDown() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + MariaDBVectorStore.quoteIdentifier(firstCollection));
            statement.executeUpdate("DROP TABLE IF EXISTS " + MariaDBVectorStore.quoteIdentifier(secondCollection));
        }
    }

    @Test
    public void shouldRoundTripConditionsAndKeepCollectionsIsolated() throws SQLException {
        StoreOptions firstOptions = StoreOptions.ofCollectionName(firstCollection);
        StoreOptions secondOptions = StoreOptions.ofCollectionName(secondCollection);
        assertTrue(store.store(Arrays.asList(
            document("doc-1", "AI content", "AI", "published", 10, new float[]{1, 0, 0}),
            document("doc-2", "Java content", "Java", "deleted", 20, new float[]{0, 1, 0}),
            document("doc-3", "Ops content", "Ops", "published", 30, new float[]{0.5f, 0.5f, 0})
        ), firstOptions).isSuccess());
        assertTrue(store.store(Collections.singletonList(
            document("other-1", "other", "Other", "published", 10, new float[]{1, 0, 0})
        ), secondOptions).isSuccess());

        SearchWrapper query = vectorSearch(new float[]{1, 0, 0})
            .condition("category IN ('AI', 'Ops') AND status NOT IN ('deleted', 'blocked')"
                + " AND views BETWEEN 5 AND 15 AND profile.level >= 2 AND active = true")
            .isNotNull("category")
            .not(group -> group.eq("status", "deleted"))
            .minScore(0.8)
            .outputVector(true);
        List<Document> found = store.search(query, firstOptions);
        assertEquals(1, found.size());
        assertEquals("doc-1", found.get(0).getId());
        assertEquals("title-doc-1", found.get(0).getTitle());
        assertEquals(3, found.get(0).getVector().length);
        assertEquals(1.0f, found.get(0).getScore(), 0.0001f);

        assertTrue(store.search(query, secondOptions).isEmpty());
        assertTrue(indexExists(firstCollection, "vector_idx"));
        assertTrue(showCreateTable(firstCollection).contains("`DISTANCE`=cosine"));
    }

    @Test
    public void shouldSupportEuclideanDistance() {
        MariaDBVectorStore euclideanStore = new MariaDBVectorStore(
            config(secondCollection, MariaDBDistanceType.EUCLIDEAN));
        StoreOptions options = StoreOptions.ofCollectionName(secondCollection);
        assertTrue(euclideanStore.store(Arrays.asList(
            document("near", "near", "AI", "published", 10, new float[]{1, 0, 0}),
            document("far", "far", "AI", "published", 10, new float[]{0, 1, 0})
        ), options).isSuccess());

        List<Document> found = euclideanStore.search(
            vectorSearch(new float[]{1, 0, 0}).minScore(0.99), options);
        assertEquals(1, found.size());
        assertEquals("near", found.get(0).getId());
        assertEquals(1.0f, found.get(0).getScore(), 0.0001f);
        assertTrue(showCreateTable(secondCollection).contains("`DISTANCE`=euclidean"));
    }

    @Test
    public void shouldSupportFilterOnlyOutputFieldsUpdateAndDelete() {
        StoreOptions options = StoreOptions.ofCollectionName(firstCollection);
        Document document = document(42, "before", "AI", "published", 10, new float[]{1, 0, 0});
        assertTrue(store.store(Collections.singletonList(document), options).isSuccess());

        SearchWrapper filterOnly = new SearchWrapper()
            .withVector(false)
            .condition("id = 42 AND category = 'AI'")
            .outputFields("metadataMap.category")
            .maxResults(10);
        Document filtered = store.search(filterOnly, options).get(0);
        assertEquals("42", filtered.getId());
        assertEquals("AI", filtered.getMetadata("category"));
        assertFalse(filtered.containsMetadata("views"));
        assertNull(filtered.getVector());
        assertNull(filtered.getScore());

        document.setContent("after");
        document.setTitle("updated-title");
        assertTrue(store.update(Collections.singletonList(document), options).isSuccess());
        Document updated = store.search(vectorSearch(new float[]{1, 0, 0}), options).get(0);
        assertEquals("after", updated.getContent());
        assertEquals("updated-title", updated.getTitle());
        assertNotNull(updated.getScore());

        StoreResult deleted = store.delete(Collections.singletonList(42), options);
        assertTrue(deleted.toString(), deleted.isSuccess());
        assertTrue(store.search(filterOnly, options).isEmpty());
    }

    private boolean indexExists(String table, String index) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics"
            + " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private String showCreateTable(String table) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "SHOW CREATE TABLE " + MariaDBVectorStore.quoteIdentifier(table))) {
            resultSet.next();
            return resultSet.getString(2);
        } catch (SQLException exception) {
            throw new AssertionError("Failed to inspect MariaDB collection", exception);
        }
    }

    private MariaDBVectorStoreConfig config(String collection, MariaDBDistanceType distanceType) {
        MariaDBVectorStoreConfig config = new MariaDBVectorStoreConfig();
        config.setHost(host);
        config.setPort(port);
        config.setDatabaseName(database);
        config.setUsername(username);
        config.setPassword(password);
        config.setDefaultCollectionName(collection);
        config.setVectorDimension(3);
        config.setDistanceType(distanceType);
        return config;
    }

    private SearchWrapper vectorSearch(float[] vector) {
        SearchWrapper wrapper = new SearchWrapper().maxResults(10);
        wrapper.setVector(vector);
        return wrapper;
    }

    private Document document(
        Object id,
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
        document.putMetadata("active", true);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("level", 2);
        document.putMetadata("profile", profile);
        return document;
    }
}

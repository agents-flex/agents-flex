/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.pgvector;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 PostgreSQL pgvector 扩展的文档向量存储。
 *
 * <p>集合名映射为数据表，文档固定字段保存为表列，其他元数据保存为 JSONB。
 * 写入操作使用事务；过滤条件由 {@link PgvectorExpressionAdaptor} 转换为参数化 SQL，
 * 避免将条件值直接拼接进语句。</p>
 */
public class PgvectorVectorStore extends DocumentStore {

    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

    private final PGSimpleDataSource dataSource;
    private final String defaultCollectionName;
    private final Integer defaultVectorDimension;
    private final PgvectorVectorStoreConfig config;

    public PgvectorVectorStore(PgvectorVectorStoreConfig config) {
        if (config == null || !config.checkAvailable()) {
            throw new IllegalArgumentException("Pgvector configuration is not available");
        }
        this.config = config;
        this.defaultCollectionName = config.getDefaultCollectionName();
        this.defaultVectorDimension = config.getVectorDimension();

        dataSource = new PGSimpleDataSource();
        dataSource.setServerNames(new String[]{config.getHost()});
        dataSource.setPortNumbers(new int[]{config.getPort()});
        dataSource.setUser(config.getUsername());
        dataSource.setPassword(config.getPassword());
        dataSource.setDatabaseName(config.getDatabaseName());
        if (config.getProperties() != null) {
            config.getProperties().forEach((key, value) -> setDataSourceProperty(key, value));
        }
        initDb();
    }

    /** 初始化 vector 扩展，并按配置创建默认集合。 */
    public final void initDb() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE EXTENSION IF NOT EXISTS vector");
            if (config.isAutoCreateCollection() && StringUtil.hasText(defaultCollectionName)) {
                createCollectionIfNotExist(connection, defaultCollectionName, defaultVectorDimension);
            }
        } catch (SQLException e) {
            throw new StoreException("Failed to initialize pgvector", e);
        }
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        String collectionName = resolveCollectionName(options);
        int dimensions = resolveDimensions(documents, options);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                createCollectionIfNotExist(connection, collectionName, dimensions);
                String sql = "INSERT INTO " + quoteIdentifier(collectionName)
                    + " (id, title, content, vector, metadata) VALUES (?, ?, ?, ?, ?::jsonb)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Document document : documents) {
                        statement.setString(1, String.valueOf(document.getId()));
                        statement.setString(2, document.getTitle());
                        statement.setString(3, document.getContent());
                        statement.setObject(4, toPgVector(document));
                        statement.setString(5, JSON.toJSONString(metadata(document)));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
                return StoreResult.successWithIds(documents);
            } catch (Exception e) {
                rollback(connection, e);
                return StoreResult.fail("Store failed: " + e.getMessage(), e);
            }
        } catch (SQLException e) {
            return StoreResult.fail("Store failed: " + e.getMessage(), e);
        }
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        String collectionName = resolveCollectionName(options);
        StringBuilder sql = new StringBuilder("DELETE FROM ")
            .append(quoteIdentifier(collectionName)).append(" WHERE id IN (");
        sql.append(String.join(", ", Collections.nCopies(ids.size(), "?"))).append(")");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Object id : ids) {
                statement.setString(index++, String.valueOf(id));
            }
            statement.executeUpdate();
            return StoreResult.success();
        } catch (Exception e) {
            return StoreResult.fail("Delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        if (wrapper == null || wrapper.getVector() == null || wrapper.getVector().length == 0) {
            return Collections.emptyList();
        }
        validateMinScore(wrapper.getMinScore());
        int maxResults = resolveMaxResults(wrapper.getMaxResults());
        String collectionName = resolveCollectionName(options);
        PgvectorExpressionAdaptor conditionBuilder = new PgvectorExpressionAdaptor();
        String condition = wrapper.toFilterExpression(conditionBuilder);

        StringBuilder sql = new StringBuilder("SELECT id, title, content, metadata, score");
        if (wrapper.isOutputVector()) {
            sql.append(", vector");
        }
        sql.append(" FROM (SELECT id, title, content, metadata, vector, 1 - (vector <=> ?) AS score FROM ")
            .append(quoteIdentifier(collectionName)).append(") ranked WHERE 1 = 1");
        if (wrapper.getMinScore() != null && wrapper.getMinScore() > 0) {
            sql.append(" AND score >= ?");
        }
        if (StringUtil.hasText(condition)) {
            sql.append(" AND (").append(condition).append(")");
        }
        sql.append(" ORDER BY score DESC LIMIT ?");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameterIndex = 1;
            statement.setObject(parameterIndex++, PgvectorUtil.toPgVector(wrapper.getVector()));
            if (wrapper.getMinScore() != null && wrapper.getMinScore() > 0) {
                statement.setDouble(parameterIndex++, wrapper.getMinScore());
            }
            for (Object parameter : conditionBuilder.getParameters()) {
                statement.setObject(parameterIndex++, parameter);
            }
            statement.setInt(parameterIndex, maxResults);

            try (ResultSet resultSet = statement.executeQuery()) {
                List<Document> documents = new ArrayList<>();
                while (resultSet.next()) {
                    Document document = new Document();
                    document.setId(resultSet.getString("id"));
                    document.setTitle(resultSet.getString("title"));
                    document.setContent(resultSet.getString("content"));
                    document.setScore(resultSet.getFloat("score"));
                    document.setMetadataMap(filterMetadata(resultSet.getString("metadata"), wrapper.getOutputFields()));
                    if (wrapper.isOutputVector()) {
                        document.setVector(PgvectorUtil.fromPgVector(resultSet.getString("vector")));
                    }
                    documents.add(document);
                }
                return documents;
            }
        } catch (Exception e) {
            throw new StoreException("Search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        String collectionName = resolveCollectionName(options);
        String sql = "UPDATE " + quoteIdentifier(collectionName)
            + " SET title = ?, content = ?, vector = ?, metadata = ?::jsonb WHERE id = ?";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (Document document : documents) {
                    statement.setString(1, document.getTitle());
                    statement.setString(2, document.getContent());
                    statement.setObject(3, toPgVector(document));
                    statement.setString(4, JSON.toJSONString(metadata(document)));
                    statement.setString(5, String.valueOf(document.getId()));
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
                return StoreResult.successWithIds(documents);
            } catch (Exception e) {
                rollback(connection, e);
                return StoreResult.fail("Update failed: " + e.getMessage(), e);
            }
        } catch (SQLException e) {
            return StoreResult.fail("Update failed: " + e.getMessage(), e);
        }
    }

    static String quoteIdentifier(String identifier) {
        if (StringUtil.noText(identifier)) {
            throw new IllegalArgumentException("Pgvector collection name cannot be blank");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private void createCollectionIfNotExist(Connection connection, String collectionName, Integer dimensions)
        throws SQLException {
        if (!config.isAutoCreateCollection()) {
            return;
        }
        if (dimensions == null || dimensions <= 0) {
            throw new IllegalArgumentException("Pgvector dimensions must be greater than zero");
        }
        String table = quoteIdentifier(collectionName);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                + " (id varchar(100) PRIMARY KEY, title text, content text, vector vector(" + dimensions
                + "), metadata jsonb NOT NULL DEFAULT '{}'::jsonb)");
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS title text");
            if (config.isUseHnswIndex()) {
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS "
                    + quoteIdentifier(collectionName + "_vector_idx") + " ON " + table
                    + " USING hnsw (vector vector_cosine_ops)");
            }
        }
    }

    private String resolveCollectionName(StoreOptions options) {
        return options.getCollectionNameOrDefault(defaultCollectionName);
    }

    private int resolveDimensions(List<Document> documents, StoreOptions options) {
        Integer configured = options.getEmbeddingOptions().getDimensions();
        if (configured != null) {
            return configured;
        }
        for (Document document : documents) {
            if (document != null && document.getVector() != null && document.getVector().length > 0) {
                return document.getVector().length;
            }
        }
        return defaultVectorDimension;
    }

    private PGobject toPgVector(Document document) throws SQLException {
        if (document == null || document.getVector() == null || document.getVector().length == 0) {
            throw new IllegalArgumentException("Pgvector document vector cannot be null or empty");
        }
        return PgvectorUtil.toPgVector(document.getVector());
    }

    private Map<String, Object> metadata(Document document) {
        return document.getMetadataMap() == null ? Collections.emptyMap() : document.getMetadataMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterMetadata(String json, List<String> outputFields) {
        Map<String, Object> metadata = JSON.parseObject(json, Map.class);
        if (outputFields == null) {
            return metadata;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String outputField : outputFields) {
            String key = normalizeMetadataField(outputField);
            if (metadata.containsKey(key)) {
                filtered.put(key, metadata.get(key));
            }
        }
        return filtered;
    }

    private String normalizeMetadataField(String field) {
        if (field.startsWith("metadataMap.")) {
            return field.substring("metadataMap.".length());
        }
        if (field.startsWith("metadata.")) {
            return field.substring("metadata.".length());
        }
        return field;
    }

    private void validateMinScore(Double minScore) {
        if (minScore != null && (minScore < 0 || minScore > 1)) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
    }

    private int resolveMaxResults(Integer maxResults) {
        int resolved = maxResults == null ? SearchWrapper.DEFAULT_MAX_RESULTS : maxResults;
        if (resolved <= 0) {
            throw new IllegalArgumentException("maxResults must be greater than zero");
        }
        return resolved;
    }

    private void setDataSourceProperty(String key, String value) {
        try {
            dataSource.setProperty(key, value);
        } catch (SQLException e) {
            throw new StoreException("Invalid PostgreSQL property: " + key, e);
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

}

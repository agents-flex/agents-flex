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
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import org.mariadb.jdbc.MariaDbDataSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.Locale;
import java.util.Map;

/**
 * 基于 MariaDB 原生 VECTOR 类型的文档向量存储。
 *
 * <p>每个 Collection 对应一张独立表，向量使用 {@code VECTOR(n)} 保存，metadata
 * 使用 JSON 保存。过滤条件通过 {@link MariaDBExpressionAdaptor} 转换为参数化 SQL。</p>
 *
 * @see <a href="https://mariadb.com/kb/en/vector-overview/">MariaDB Vector Overview</a>
 */
public class MariaDBVectorStore extends DocumentStore {

    private final MariaDbDataSource dataSource;
    private final MariaDBVectorStoreConfig config;

    public MariaDBVectorStore(MariaDBVectorStoreConfig config) {
        if (config == null || !config.checkAvailable()) {
            throw new IllegalArgumentException("MariaDB configuration is not available.");
        }
        this.config = config;
        try {
            this.dataSource = new MariaDbDataSource(jdbcUrl(config));
            this.dataSource.setUser(config.getUsername());
            this.dataSource.setPassword(config.getPassword());
        } catch (SQLException exception) {
            throw new StoreException("Failed to configure MariaDB data source.", exception);
        }
        initializeDefaultCollection();
    }

    private void initializeDefaultCollection() {
        if (!config.isAutoCreateCollection()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            createCollectionIfNotExist(connection, config.getDefaultCollectionName(), config.getVectorDimension());
        } catch (SQLException exception) {
            throw new StoreException("Failed to initialize MariaDB vector store.", exception);
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
                    + " (`id`, `title`, `content`, `vector`, `metadata`)"
                    + " VALUES (?, ?, ?, VEC_FromText(?), ?)"
                    + " ON DUPLICATE KEY UPDATE `title` = VALUES(`title`),"
                    + " `content` = VALUES(`content`), `vector` = VALUES(`vector`),"
                    + " `metadata` = VALUES(`metadata`)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Document document : documents) {
                        validateDocument(document);
                        statement.setString(1, String.valueOf(document.getId()));
                        statement.setString(2, document.getTitle());
                        statement.setString(3, document.getContent());
                        statement.setString(4, MariaDBVectorUtil.toText(document.getVector()));
                        statement.setString(5, JSON.toJSONString(metadata(document)));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
                return StoreResult.successWithIds(documents);
            } catch (Exception exception) {
                rollback(connection, exception);
                return StoreResult.fail("MariaDB store failed: " + exception.getMessage(), exception);
            }
        } catch (SQLException exception) {
            return StoreResult.fail("MariaDB store failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        StringBuilder sql = new StringBuilder("DELETE FROM ")
            .append(quoteIdentifier(resolveCollectionName(options)))
            .append(" WHERE `id` IN (")
            .append(String.join(", ", Collections.nCopies(ids.size(), "?")))
            .append(')');
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Object id : ids) {
                if (id == null) {
                    throw new IllegalArgumentException("MariaDB document ID must not be null.");
                }
                statement.setString(index++, String.valueOf(id));
            }
            statement.executeUpdate();
            return StoreResult.success();
        } catch (Exception exception) {
            return StoreResult.fail("MariaDB delete failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        if (wrapper == null) {
            throw new IllegalArgumentException("SearchWrapper must not be null.");
        }
        validateMinScore(wrapper.getMinScore());
        int maxResults = resolveMaxResults(wrapper.getMaxResults());
        MariaDBExpressionAdaptor adaptor = new MariaDBExpressionAdaptor();
        String condition = wrapper.toFilterExpression(adaptor);
        boolean vectorSearch = wrapper.isWithVector();
        if (vectorSearch && (wrapper.getVector() == null || wrapper.getVector().length == 0)) {
            throw new IllegalArgumentException(
                "MariaDB vector query requires a vector; use withVector(false) for filter-only queries.");
        }

        String sql = vectorSearch
            ? vectorSearchSql(resolveCollectionName(options), wrapper, condition)
            : filterSearchSql(resolveCollectionName(options), wrapper, condition);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameterIndex = 1;
            if (vectorSearch) {
                statement.setString(parameterIndex++, MariaDBVectorUtil.toText(wrapper.getVector()));
                if (wrapper.getMinScore() != null) {
                    statement.setDouble(parameterIndex++, wrapper.getMinScore());
                }
            }
            for (Object parameter : adaptor.getParameters()) {
                statement.setObject(parameterIndex++, parameter);
            }
            statement.setInt(parameterIndex, maxResults);
            return readDocuments(statement, wrapper, vectorSearch);
        } catch (SQLException exception) {
            throw new StoreException("MariaDB search failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        String sql = "UPDATE " + quoteIdentifier(resolveCollectionName(options))
            + " SET `title` = ?, `content` = ?, `vector` = VEC_FromText(?), `metadata` = ? WHERE `id` = ?";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (Document document : documents) {
                    validateDocument(document);
                    statement.setString(1, document.getTitle());
                    statement.setString(2, document.getContent());
                    statement.setString(3, MariaDBVectorUtil.toText(document.getVector()));
                    statement.setString(4, JSON.toJSONString(metadata(document)));
                    statement.setString(5, String.valueOf(document.getId()));
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
                return StoreResult.successWithIds(documents);
            } catch (Exception exception) {
                rollback(connection, exception);
                return StoreResult.fail("MariaDB update failed: " + exception.getMessage(), exception);
            }
        } catch (SQLException exception) {
            return StoreResult.fail("MariaDB update failed: " + exception.getMessage(), exception);
        }
    }

    static String quoteIdentifier(String identifier) {
        if (StringUtil.noText(identifier)) {
            throw new IllegalArgumentException("MariaDB collection name cannot be blank.");
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    private String vectorSearchSql(String collectionName, SearchWrapper wrapper, String condition) {
        String distance = config.getDistanceType().getFunctionName() + "(`vector`, VEC_FromText(?))";
        String score = config.getDistanceType() == MariaDBDistanceType.COSINE
            ? "1 - " + distance
            : "1 / (1 + " + distance + ")";
        StringBuilder sql = new StringBuilder("SELECT `id`, `title`, `content`, `metadata`, `score`");
        if (wrapper.isOutputVector()) {
            sql.append(", VEC_ToText(`vector`) AS `vector_text`");
        }
        sql.append(" FROM (SELECT `id`, `title`, `content`, `metadata`, `vector`, ")
            .append(score).append(" AS `score` FROM ")
            .append(quoteIdentifier(collectionName)).append(") AS `ranked` WHERE 1 = 1");
        if (wrapper.getMinScore() != null) {
            sql.append(" AND `score` >= ?");
        }
        appendCondition(sql, condition);
        return sql.append(" ORDER BY `score` DESC LIMIT ?").toString();
    }

    private String filterSearchSql(String collectionName, SearchWrapper wrapper, String condition) {
        StringBuilder sql = new StringBuilder("SELECT `id`, `title`, `content`, `metadata`, NULL AS `score`");
        if (wrapper.isOutputVector()) {
            sql.append(", VEC_ToText(`vector`) AS `vector_text`");
        }
        sql.append(" FROM ").append(quoteIdentifier(collectionName)).append(" WHERE 1 = 1");
        appendCondition(sql, condition);
        return sql.append(" ORDER BY `id` LIMIT ?").toString();
    }

    private void appendCondition(StringBuilder sql, String condition) {
        if (StringUtil.hasText(condition)) {
            sql.append(" AND (").append(condition).append(')');
        }
    }

    private List<Document> readDocuments(
        PreparedStatement statement,
        SearchWrapper wrapper,
        boolean vectorSearch
    ) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<Document> documents = new ArrayList<>();
            while (resultSet.next()) {
                Document document = new Document();
                document.setId(resultSet.getString("id"));
                document.setTitle(resultSet.getString("title"));
                document.setContent(resultSet.getString("content"));
                if (vectorSearch) {
                    document.setScore(resultSet.getFloat("score"));
                }
                document.setMetadataMap(filterMetadata(
                    resultSet.getString("metadata"), wrapper.getOutputFields()));
                if (wrapper.isOutputVector()) {
                    document.setVector(MariaDBVectorUtil.fromText(resultSet.getString("vector_text")));
                }
                documents.add(document);
            }
            return documents;
        }
    }

    private void createCollectionIfNotExist(Connection connection, String collectionName, int dimensions)
        throws SQLException {
        if (!config.isAutoCreateCollection()) {
            return;
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("MariaDB vector dimensions must be greater than zero.");
        }
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
            .append(quoteIdentifier(collectionName))
            .append(" (`id` VARCHAR(191) NOT NULL PRIMARY KEY, `title` TEXT, `content` LONGTEXT,")
            .append(" `metadata` JSON NOT NULL, `vector` VECTOR(").append(dimensions).append(") NOT NULL");
        if (config.isUseVectorIndex()) {
            ddl.append(", VECTOR INDEX `vector_idx` (`vector`) DISTANCE=")
                .append(config.getDistanceType().name().toLowerCase(Locale.ROOT));
        }
        ddl.append(") ENGINE=InnoDB");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl.toString());
        }
    }

    private String resolveCollectionName(StoreOptions options) {
        StoreOptions resolved = options == null ? StoreOptions.DEFAULT : options;
        String collectionName = resolved.getCollectionNameOrDefault(config.getDefaultCollectionName());
        if (!StringUtil.hasText(collectionName)) {
            throw new IllegalArgumentException("MariaDB collection name cannot be blank.");
        }
        return collectionName;
    }

    private int resolveDimensions(List<Document> documents, StoreOptions options) {
        StoreOptions resolved = options == null ? StoreOptions.DEFAULT : options;
        if (resolved.getEmbeddingOptions() != null
            && resolved.getEmbeddingOptions().getDimensions() != null) {
            return resolved.getEmbeddingOptions().getDimensions();
        }
        for (Document document : documents) {
            if (document != null && document.getVector() != null && document.getVector().length > 0) {
                return document.getVector().length;
            }
        }
        return config.getVectorDimension();
    }

    private void validateDocument(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("MariaDB document must not be null.");
        }
        if (document.getId() == null) {
            throw new IllegalArgumentException("MariaDB document ID must not be null.");
        }
        MariaDBVectorUtil.toText(document.getVector());
    }

    private Map<String, Object> metadata(Document document) {
        return document.getMetadataMap() == null ? Collections.emptyMap() : document.getMetadataMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterMetadata(String json, List<String> outputFields) {
        Map<String, Object> metadata = JSON.parseObject(json, Map.class);
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }
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
            throw new IllegalArgumentException("minScore must be between 0 and 1.");
        }
    }

    private int resolveMaxResults(Integer maxResults) {
        int resolved = maxResults == null ? SearchWrapper.DEFAULT_MAX_RESULTS : maxResults;
        if (resolved <= 0) {
            throw new IllegalArgumentException("maxResults must be greater than zero.");
        }
        return resolved;
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

    private static String jdbcUrl(MariaDBVectorStoreConfig config) {
        StringBuilder url = new StringBuilder("jdbc:mariadb://")
            .append(config.getHost()).append(':').append(config.getPort()).append('/')
            .append(encode(config.getDatabaseName()));
        Map<String, String> properties = config.getProperties();
        if (properties != null && !properties.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_.-]+")) {
                    throw new IllegalArgumentException("Invalid MariaDB connection property: " + entry.getKey());
                }
                url.append(first ? '?' : '&').append(entry.getKey()).append('=').append(encode(entry.getValue()));
                first = false;
            }
        }
        return url.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to encode MariaDB connection property.", exception);
        }
    }
}

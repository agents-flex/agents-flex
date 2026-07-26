/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.clickhouse;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 ClickHouse 25.8+ vector_similarity 索引的文档向量存储。
 *
 * <p>每个 Collection 映射为一张 MergeTree 表，向量保存为 {@code Array(Float32)}，metadata
 * 保存为 JSON 字符串。向量查询使用 cosineDistance、L2Distance 或 dotProduct，过滤条件由
 * {@link ClickHouseExpressionAdaptor} 转换为参数化 SQL 并在 ClickHouse 服务端执行。</p>
 *
 * <p>ClickHouse 面向分析型追加写入，不提供普通关系数据库式的行级 upsert。本实现为了保证业务 ID
 * 唯一，会先执行同步 DELETE mutation，再插入新版本。该过程不是事务，并且高频更新成本较高，适合
 * 以批量导入和检索为主、更新频率较低的知识库。</p>
 */
public class ClickHouseVectorStore extends DocumentStore {
    private static final String VECTOR_INDEX = "vector_idx";
    private static final String IDENTIFIER_PATTERN = "[A-Za-z][A-Za-z0-9_]{0,63}";

    private final ClickHouseVectorStoreConfig config;
    private final String defaultJdbcUrl;
    private final String databaseJdbcUrl;
    private final Properties connectionProperties;
    /** DDL 和覆盖写入都按 Collection 串行化，避免同一实例内产生重复业务 ID。 */
    private final Map<String, Object> collectionLocks = new ConcurrentHashMap<>();

    public ClickHouseVectorStore(ClickHouseVectorStoreConfig config) {
        if (config == null || !config.checkAvailable()) {
            throw new IllegalArgumentException("ClickHouse vector store configuration is incomplete");
        }
        this.config = config;
        validateIdentifier(config.getDatabaseName(), "database");
        validateIdentifier(config.getDefaultCollectionName(), "collection");
        validateConnectionProperties(config.getProperties());
        try {
            Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        } catch (ClassNotFoundException exception) {
            throw new StoreException("ClickHouse JDBC driver is not available", exception);
        }
        this.defaultJdbcUrl = jdbcUrl("default");
        this.databaseJdbcUrl = jdbcUrl(config.getDatabaseName());
        this.connectionProperties = new Properties();
        connectionProperties.setProperty("user", config.getUsername());
        connectionProperties.setProperty("password", config.getPassword() == null ? "" : config.getPassword());
        initializeDatabase();
        if (config.isAutoCreateCollection()) {
            ensureCollection(config.getDefaultCollectionName());
        }
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        return write(documents, options, "store");
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        // ClickHouse 没有低成本的逐行 UPDATE，覆盖写入统一采用同步删除后插入。
        return write(documents, options, "update");
    }

    private StoreResult write(List<Document> documents, StoreOptions options, String operation) {
        if (documents == null || documents.isEmpty()) return StoreResult.success();
        try {
            for (Document document : documents) validateDocument(document);
            String collection = resolveCollection(options);
            Object lock = collectionLocks.computeIfAbsent(collection, key -> new Object());
            synchronized (lock) {
                ensureCollection(collection);
                deleteIds(collection, documentIds(documents));
                insertDocuments(collection, documents);
            }
            return StoreResult.successWithIds(documents);
        } catch (Exception exception) {
            return StoreResult.fail("ClickHouse " + operation + " failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) return StoreResult.success();
        try {
            String collection = resolveCollection(options);
            Object lock = collectionLocks.computeIfAbsent(collection, key -> new Object());
            synchronized (lock) {
                ensureCollection(collection);
                deleteIds(collection, ids);
            }
            return StoreResult.success();
        } catch (Exception exception) {
            return StoreResult.fail("ClickHouse delete failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        Objects.requireNonNull(wrapper, "SearchWrapper cannot be null").validate();
        String collection = resolveCollection(options);
        ensureCollection(collection);
        if (wrapper.isWithVector()) {
            validateVector(wrapper.getVector());
            if (wrapper.getMaxResults() > config.getMaxVectorSearchResults()) {
                throw new IllegalArgumentException("ClickHouse vector maxResults must not exceed "
                    + config.getMaxVectorSearchResults());
            }
        }

        ClickHouseExpressionAdaptor adaptor = new ClickHouseExpressionAdaptor();
        String condition = wrapper.getCondition() == null ? ""
            : wrapper.getCondition().toExpression(adaptor);
        String sql = wrapper.isWithVector()
            ? vectorSearchSql(collection, wrapper, condition)
            : filterSearchSql(collection, wrapper, condition);
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (Object value : adaptor.getParameters()) {
                statement.setObject(parameter++, value);
            }
            statement.setInt(parameter, wrapper.getMaxResults());
            List<Document> documents = readDocuments(statement, wrapper);
            if (wrapper.getMinScore() != null) {
                documents.removeIf(document -> document.getScore() == null
                    || document.getScore() < wrapper.getMinScore().floatValue());
            }
            return documents;
        } catch (SQLException exception) {
            throw new StoreException("ClickHouse search failed: " + exception.getMessage(), exception);
        }
    }

    private String vectorSearchSql(String collection, SearchWrapper wrapper, String condition) {
        String distance = config.getSimilarity().getFunctionName() + "(`vector`, `query_vector`)";
        // JDBC 0.8.x 的 Java 8 兼容驱动无法正确解析 CAST(?) 内的参数数量。向量已经过有限数值和
        // 维度校验，因此这里生成只含 Float 文本的受控数组字面量，其他业务条件仍全部使用参数绑定。
        String vectorLiteral = ClickHouseVectorUtil.toText(wrapper.getVector());
        StringBuilder sql = new StringBuilder("WITH CAST(").append(vectorLiteral)
            .append(" AS Array(Float32)) AS `query_vector` SELECT ")
            .append("`id`, `title`, `content`, `metadata`, ")
            .append(config.getSimilarity().scoreExpression(distance)).append(" AS `score`");
        if (wrapper.isOutputVector()) sql.append(", `vector`");
        sql.append(" FROM ").append(table(collection));
        appendCondition(sql, condition);
        sql.append(" ORDER BY ").append(distance).append(' ').append(config.getSimilarity().getOrder())
            .append(" LIMIT ? SETTINGS function_json_value_return_type_allow_nullable = 1")
            .append(", hnsw_candidate_list_size_for_search = ").append(config.getHnswEfSearch());
        if (StringUtil.hasText(condition)) {
            // prefilter 保证权限、租户等条件先于距离排序生效，不会因 ANN 后过滤导致返回不足或越权结果。
            sql.append(", vector_search_filter_strategy = 'prefilter'");
        }
        return sql.toString();
    }

    private String filterSearchSql(String collection, SearchWrapper wrapper, String condition) {
        StringBuilder sql = new StringBuilder("SELECT `id`, `title`, `content`, `metadata`, NULL AS `score`");
        if (wrapper.isOutputVector()) sql.append(", `vector`");
        sql.append(" FROM ").append(table(collection));
        appendCondition(sql, condition);
        return sql.append(" ORDER BY `id` LIMIT ? SETTINGS function_json_value_return_type_allow_nullable = 1")
            .toString();
    }

    private void appendCondition(StringBuilder sql, String condition) {
        if (StringUtil.hasText(condition)) sql.append(" WHERE (").append(condition).append(')');
    }

    private void insertDocuments(String collection, List<Document> documents) throws SQLException {
        String sql = "INSERT INTO " + table(collection)
            + " (`id`, `title`, `content`, `vector`, `metadata`)"
            + " VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Document document : documents) {
                statement.setString(1, String.valueOf(document.getId()));
                setNullableString(statement, 2, document.getTitle());
                setNullableString(statement, 3, document.getContent());
                // setObject(float[]) 在 JDBC 0.8.x 的通用 SQL 路径中会退化为 "[F@..."，必须走数组接口。
                statement.setArray(4, new Float32SqlArray(document.getVector()));
                statement.setString(5, JSON.toJSONString(document.getMetadataMap()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteIds(String collection, Collection<?> ids) throws SQLException {
        StringJoiner placeholders = new StringJoiner(", ", "(", ")");
        for (Object ignored : ids) placeholders.add("?");
        String sql = "ALTER TABLE " + table(collection) + " DELETE WHERE `id` IN " + placeholders
            + " SETTINGS mutations_sync = 2";
        try (Connection connection = openDatabaseConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Object id : ids) {
                if (id == null) throw new IllegalArgumentException("ClickHouse document ID cannot be null");
                statement.setString(index++, String.valueOf(id));
            }
            statement.executeUpdate();
        }
    }

    private List<Document> readDocuments(PreparedStatement statement, SearchWrapper wrapper) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<Document> result = new ArrayList<>();
            while (resultSet.next()) {
                Document document = new Document();
                document.setId(resultSet.getString("id"));
                document.setTitle(resultSet.getString("title"));
                document.setContent(resultSet.getString("content"));
                document.setMetadataMap(filterMetadata(resultSet.getString("metadata"), wrapper.getOutputFields()));
                if (wrapper.isWithVector()) document.setScore(resultSet.getFloat("score"));
                if (wrapper.isOutputVector()) {
                    document.setVector(ClickHouseVectorUtil.fromText(resultSet.getString("vector")));
                }
                result.add(document);
            }
            return result;
        }
    }

    private void initializeDatabase() {
        try (Connection connection = openConnection(defaultJdbcUrl);
             PreparedStatement check = connection.prepareStatement(
                 "SELECT count() FROM system.databases WHERE name = ?")) {
            check.setString(1, config.getDatabaseName());
            boolean exists;
            try (ResultSet resultSet = check.executeQuery()) {
                resultSet.next();
                exists = resultSet.getLong(1) > 0;
            }
            if (!exists && !config.isAutoCreateDatabase()) {
                throw new StoreException("ClickHouse database does not exist: " + config.getDatabaseName());
            }
            if (!exists) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE DATABASE " + quoteIdentifier(config.getDatabaseName()));
                }
            }
        } catch (SQLException exception) {
            throw new StoreException("Failed to initialize ClickHouse database", exception);
        }
    }

    private void ensureCollection(String collection) {
        validateIdentifier(collection, "collection");
        Object lock = collectionLocks.computeIfAbsent(collection, key -> new Object());
        synchronized (lock) {
            try (Connection connection = openDatabaseConnection()) {
                boolean exists = tableExists(connection, collection);
                if (!exists && !config.isAutoCreateCollection()) {
                    throw new StoreException("ClickHouse collection does not exist: " + collection);
                }
                if (!exists) createCollection(connection, collection);
                validateCollection(connection, collection);
                ensureVectorIndex(connection, collection);
            } catch (SQLException exception) {
                throw new StoreException("Failed to initialize ClickHouse collection " + collection, exception);
            }
        }
    }

    private boolean tableExists(Connection connection, String collection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT count() FROM system.tables WHERE database = ? AND name = ?")) {
            statement.setString(1, config.getDatabaseName());
            statement.setString(2, collection);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    private void createCollection(Connection connection, String collection) throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table(collection)).append(" (")
            .append("`id` String, `title` Nullable(String), `content` Nullable(String), ")
            .append("`vector` Array(Float32), `metadata` String, ")
            .append("CONSTRAINT `vector_dimension` CHECK length(`vector`) = ")
            .append(config.getVectorDimension());
        if (shouldCreateVectorIndex()) sql.append(", ").append(indexDefinition());
        sql.append(") ENGINE = MergeTree ORDER BY `id`");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql.toString());
        }
    }

    private void validateCollection(Connection connection, String collection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT type FROM system.columns WHERE database = ? AND table = ? AND name = 'vector'")) {
            statement.setString(1, config.getDatabaseName());
            statement.setString(2, collection);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || !"Array(Float32)".equals(resultSet.getString(1))) {
                    throw new StoreException("ClickHouse collection vector column must be Array(Float32)");
                }
            }
        }
        String ddl = showCreateTable(connection, collection);
        if (!ddl.contains("length(vector) = " + config.getVectorDimension())
            && !ddl.contains("length(`vector`) = " + config.getVectorDimension())) {
            throw new StoreException("ClickHouse collection vector dimension does not match "
                + config.getVectorDimension());
        }
        if (hasVectorIndex(connection, collection)
            && !ddl.contains("'" + config.getSimilarity().getFunctionName() + "'")) {
            throw new StoreException("ClickHouse vector index distance function does not match "
                + config.getSimilarity().getFunctionName());
        }
    }

    private void ensureVectorIndex(Connection connection, String collection) throws SQLException {
        if (!shouldCreateVectorIndex() || hasVectorIndex(connection, collection)) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table(collection) + " ADD INDEX " + indexDefinition());
            statement.executeUpdate("ALTER TABLE " + table(collection)
                + " MATERIALIZE INDEX `" + VECTOR_INDEX + "` SETTINGS mutations_sync = 2");
        }
    }

    private boolean hasVectorIndex(Connection connection, String collection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT count() FROM system.data_skipping_indices"
                + " WHERE database = ? AND table = ? AND name = ?")) {
            statement.setString(1, config.getDatabaseName());
            statement.setString(2, collection);
            statement.setString(3, VECTOR_INDEX);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    private String showCreateTable(Connection connection, String collection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW CREATE TABLE " + table(collection))) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private String indexDefinition() {
        return "INDEX `" + VECTOR_INDEX + "` `vector` TYPE vector_similarity('hnsw', '"
            + config.getSimilarity().getFunctionName() + "', " + config.getVectorDimension() + ", '"
            + config.getQuantization() + "', " + config.getHnswM() + ", "
            + config.getHnswEfConstruction() + ")";
    }

    private boolean shouldCreateVectorIndex() {
        return config.isAutoCreateVectorIndex() && config.getSimilarity().isVectorIndexSupported();
    }

    private Connection openDatabaseConnection() throws SQLException {
        return openConnection(databaseJdbcUrl);
    }

    private Connection openConnection(String url) throws SQLException {
        return DriverManager.getConnection(url, connectionProperties);
    }

    private String jdbcUrl(String database) {
        StringBuilder url = new StringBuilder("jdbc:clickhouse:http://")
            .append(config.getHost()).append(':').append(config.getPort()).append('/')
            .append(encode(database)).append("?socket_timeout=").append(config.getRequestTimeoutMillis())
            .append("&connection_timeout=").append(config.getRequestTimeoutMillis())
            .append("&jdbc_ignore_unsupported_values=true");
        for (Map.Entry<String, String> entry : config.getProperties().entrySet()) {
            url.append('&').append(entry.getKey()).append('=').append(encode(entry.getValue()));
        }
        return url.toString();
    }

    private String resolveCollection(StoreOptions options) {
        String collection = options == null ? config.getDefaultCollectionName()
            : options.getCollectionNameOrDefault(config.getDefaultCollectionName());
        validateIdentifier(collection, "collection");
        return collection;
    }

    private void validateDocument(Document document) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("ClickHouse document and ID cannot be null");
        }
        validateVector(document.getVector());
        // 提前序列化一次，确保不支持的 metadata 值在执行删除 mutation 前失败。
        JSON.toJSONString(document.getMetadataMap());
    }

    private void validateVector(float[] vector) {
        ClickHouseVectorUtil.toText(vector);
        if (vector.length != config.getVectorDimension()) {
            throw new IllegalArgumentException("ClickHouse vector dimension must be " + config.getVectorDimension());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterMetadata(String json, List<String> outputFields) {
        Map<String, Object> metadata = JSON.parseObject(json, Map.class);
        if (metadata == null) metadata = Collections.emptyMap();
        if (outputFields == null) return metadata;
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String field : outputFields) {
            String key = normalizeOutputField(field);
            if (metadata.containsKey(key)) filtered.put(key, metadata.get(key));
        }
        return filtered;
    }

    private String normalizeOutputField(String field) {
        String normalized = field == null ? "" : field.trim();
        if (normalized.startsWith("metadataMap.")) return normalized.substring("metadataMap.".length());
        if (normalized.startsWith("metadata.")) return normalized.substring("metadata.".length());
        return normalized;
    }

    private String table(String collection) {
        return quoteIdentifier(config.getDatabaseName()) + "." + quoteIdentifier(collection);
    }

    static String quoteIdentifier(String identifier) {
        validateIdentifier(identifier, "identifier");
        return "`" + identifier + "`";
    }

    private static void validateIdentifier(String identifier, String label) {
        if (identifier == null || !identifier.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException("Invalid ClickHouse " + label + ": " + identifier);
        }
    }

    private static void validateConnectionProperties(Map<String, String> properties) {
        for (String key : properties.keySet()) {
            if (key == null || !key.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("Invalid ClickHouse JDBC property: " + key);
            }
        }
    }

    private static List<Object> documentIds(List<Document> documents) {
        List<Object> ids = new ArrayList<>(documents.size());
        for (Document document : documents) ids.add(document.getId());
        return ids;
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to encode ClickHouse JDBC value", exception);
        }
    }

    /**
     * 仅用于向 ClickHouse JDBC 传递 {@code Array(Float32)} 的只读适配器。
     *
     * <p>驱动写入时只调用 {@link #getArray()}；切片和结果集视图属于 JDBC 读取能力，本适配器不承担，
     * 因此明确抛出不支持异常，避免调用方误以为它是一个完整的数据库数组对象。</p>
     */
    private static final class Float32SqlArray implements Array {
        private Float[] values;

        private Float32SqlArray(float[] values) {
            this.values = new Float[values.length];
            for (int i = 0; i < values.length; i++) this.values[i] = values[i];
        }

        @Override
        public String getBaseTypeName() throws SQLException {
            ensureAvailable();
            return "Float32";
        }

        @Override
        public int getBaseType() throws SQLException {
            ensureAvailable();
            return Types.FLOAT;
        }

        @Override
        public Object getArray() throws SQLException {
            ensureAvailable();
            return values;
        }

        @Override
        public Object getArray(Map<String, Class<?>> map) throws SQLException {
            return getArray();
        }

        @Override
        public Object getArray(long index, int count) throws SQLException {
            throw unsupported();
        }

        @Override
        public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
            throw unsupported();
        }

        @Override
        public ResultSet getResultSet() throws SQLException {
            throw unsupported();
        }

        @Override
        public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
            throw unsupported();
        }

        @Override
        public ResultSet getResultSet(long index, int count) throws SQLException {
            throw unsupported();
        }

        @Override
        public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
            throw unsupported();
        }

        @Override
        public void free() {
            values = null;
        }

        private void ensureAvailable() throws SQLException {
            if (values == null) throw new SQLException("ClickHouse Float32 SQL array has been freed");
        }

        private SQLFeatureNotSupportedException unsupported() {
            return new SQLFeatureNotSupportedException("ClickHouse Float32 SQL array view is not supported");
        }
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.cassandra;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.CqlVector;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * 基于 Apache Cassandra 5.x 原生 vector 类型与 Storage-Attached Indexes 的向量存储。
 *
 * <p>一个 {@link StoreOptions#getCollectionName()} 对应一张 Cassandra 表。文档 ID、标题、
 * 正文和向量使用固定列，metadata 使用 {@code metadata_字段名} 显式列保存并建立 SAI，
 * 从而让属性条件与 ANN 在 Cassandra 服务端共同执行。</p>
 *
 * <p>Cassandra CQL 没有通用 OR，IN 与 ANN 也不能直接组合。模块会把这两类条件规划为
 * 多条参数化查询，随后按 ID 去重、按 score 全局排序。NE、NOT IN、NULL 和 NOT 不会用
 * ALLOW FILTERING 模拟，而是明确拒绝，避免返回不完整结果。</p>
 */
public class CassandraVectorStore extends DocumentStore implements AutoCloseable {

    private static final int MAX_ANN_RESULTS = 1000;
    private static final String METADATA_PREFIX = "metadata_";
    private static final String IDENTIFIER_PATTERN = "[A-Za-z][A-Za-z0-9_]{0,47}";

    private final CassandraVectorStoreConfig config;
    private final CqlSession session;
    private final Map<String, Object> schemaLocks = new ConcurrentHashMap<>();
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();

    public CassandraVectorStore(CassandraVectorStoreConfig config) {
        if (config == null || !config.checkAvailable()) {
            throw new IllegalArgumentException("Cassandra configuration is not available.");
        }
        this.config = config;
        validateIdentifier(config.getKeyspace(), "keyspace");
        validateIdentifier(config.getDefaultCollectionName(), "collection");
        for (String field : config.getMetadataFieldTypes().keySet()) {
            validateIdentifier(field, "metadata field");
        }
        CqlSession openedSession = null;
        try {
            openedSession = buildSession(config);
            this.session = openedSession;
            initializeSchema();
        } catch (RuntimeException exception) {
            if (openedSession != null) {
                openedSession.close();
            }
            throw new StoreException("Failed to initialize Cassandra vector store.", exception);
        }
    }

    private CqlSession buildSession(CassandraVectorStoreConfig config) {
        CqlSessionBuilder builder = CqlSession.builder()
            .withLocalDatacenter(config.getLocalDatacenter());
        for (String configuredPoint : config.getContactPoint().split(",")) {
            String point = configuredPoint.trim();
            int separator = point.lastIndexOf(':');
            if (separator <= 0 || separator == point.length() - 1) {
                throw new IllegalArgumentException("Cassandra contactPoint must use host:port format.");
            }
            String host = point.substring(0, separator);
            int port = Integer.parseInt(point.substring(separator + 1));
            builder.addContactPoint(new InetSocketAddress(host, port));
        }
        if (StringUtil.hasText(config.getUsername())) {
            builder.withAuthCredentials(config.getUsername(), config.getPassword() == null ? "" : config.getPassword());
        }
        return builder.build();
    }

    private void initializeSchema() {
        if (config.isAutoCreateKeyspace()) {
            executeSchema("CREATE KEYSPACE IF NOT EXISTS " + quote(config.getKeyspace())
                + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '"
                + config.getReplicationFactor() + "'}");
        }
        if (config.isAutoCreateCollection()) {
            ensureCollection(config.getDefaultCollectionName(), Collections.emptyList());
        }
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        return write(documents, options, "store");
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        // Cassandra 的 INSERT 本身就是原子 upsert。这里写入所有已知 metadata 列，缺失值绑定
        // 为 null，从而保证 update/store 都是整份文档覆盖，而不是残留上一次的 metadata。
        return write(documents, options, "update");
    }

    private StoreResult write(List<Document> documents, StoreOptions options, String operation) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        String collection = resolveCollection(options);
        try {
            for (Document document : documents) {
                validateDocument(document);
            }
            ensureCollection(collection, documents);
            Map<String, CassandraMetadataType> columns = metadataColumns(collection);
            String cql = insertCql(collection, columns.keySet());
            PreparedStatement prepared = session.prepare(cql);
            for (Document document : documents) {
                BoundStatementBuilder statement = prepared.boundStatementBuilder();
                setNullableString(statement, 0, String.valueOf(document.getId()));
                setNullableString(statement, 1, document.getTitle());
                setNullableString(statement, 2, document.getContent());
                statement.setVector(3, vector(document.getVector()), Float.class);
                int index = 4;
                Map<String, Object> metadata = document.getMetadataMap();
                for (Map.Entry<String, CassandraMetadataType> column : columns.entrySet()) {
                    String field = column.getKey().substring(METADATA_PREFIX.length());
                    Object value = metadata == null ? null : metadata.get(field);
                    setMetadataValue(statement, index++, value, column.getValue());
                }
                session.execute(statement
                    .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_ONE)
                    .setTimeout(Duration.ofMillis(config.getRequestTimeoutMillis()))
                    .setIdempotence(true).build());
            }
            return StoreResult.successWithIds(documents);
        } catch (Exception exception) {
            return StoreResult.fail("Cassandra " + operation + " failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        try {
            String collection = resolveCollection(options);
            String cql = "DELETE FROM " + table(collection) + " WHERE " + quote("id") + " IN ("
                + String.join(", ", Collections.nCopies(ids.size(), "?")) + ')';
            BoundStatementBuilder statement = session.prepare(cql).boundStatementBuilder();
            int index = 0;
            for (Object id : ids) {
                if (id == null) {
                    throw new IllegalArgumentException("Cassandra document ID must not be null.");
                }
                statement.setString(index++, String.valueOf(id));
            }
            session.execute(statement.setConsistencyLevel(DefaultConsistencyLevel.LOCAL_ONE)
                .setTimeout(Duration.ofMillis(config.getRequestTimeoutMillis()))
                .setIdempotence(true).build());
            return StoreResult.success();
        } catch (Exception exception) {
            return StoreResult.fail("Cassandra delete failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        if (wrapper == null) {
            throw new IllegalArgumentException("SearchWrapper must not be null.");
        }
        int limit = wrapper.getMaxResults() == null ? SearchWrapper.DEFAULT_MAX_RESULTS : wrapper.getMaxResults();
        if (limit > MAX_ANN_RESULTS) {
            throw new IllegalArgumentException("Cassandra ANN maxResults must not exceed 1000.");
        }
        if (wrapper.isWithVector()) {
            validateVector(wrapper.getVector());
        }

        String collection = resolveCollection(options);
        ensureCollection(collection, Collections.emptyList());
        Map<String, CassandraMetadataType> metadataColumns = metadataColumns(collection);
        List<List<CassandraConditionPlanner.Predicate>> branches =
            CassandraConditionPlanner.plan(wrapper.getCondition());
        Map<String, Document> merged = new LinkedHashMap<>();
        for (List<CassandraConditionPlanner.Predicate> branch : branches) {
            validatePredicates(branch, metadataColumns);
            for (Document document : executeSearchBranch(
                collection, wrapper, limit, branch, metadataColumns)) {
                String id = String.valueOf(document.getId());
                Document previous = merged.get(id);
                if (previous == null || score(document) > score(previous)) {
                    merged.put(id, document);
                }
            }
        }

        List<Document> result = new ArrayList<>(merged.values());
        if (wrapper.isWithVector()) {
            result.sort(Comparator.comparing(CassandraVectorStore::score).reversed()
                .thenComparing(document -> String.valueOf(document.getId())));
        } else {
            result.sort(Comparator.comparing(document -> String.valueOf(document.getId())));
        }
        if (result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    private List<Document> executeSearchBranch(
        String collection,
        SearchWrapper wrapper,
        int limit,
        List<CassandraConditionPlanner.Predicate> predicates,
        Map<String, CassandraMetadataType> metadataColumns
    ) {
        List<String> selectedMetadata = selectedMetadata(wrapper, metadataColumns.keySet());
        StringBuilder cql = new StringBuilder("SELECT ")
            .append(quote("id")).append(", ").append(quote("title")).append(", ")
            .append(quote("content"));
        for (String column : selectedMetadata) {
            cql.append(", ").append(quote(column));
        }
        if (wrapper.isOutputVector()) {
            cql.append(", ").append(quote("embedding"));
        }
        if (wrapper.isWithVector()) {
            cql.append(", ").append(config.getSimilarity().getFunctionName())
                .append('(').append(quote("embedding")).append(", ?) AS ").append(quote("score"));
        }
        cql.append(" FROM ").append(table(collection));
        appendPredicates(cql, predicates);
        if (wrapper.isWithVector()) {
            cql.append(" ORDER BY ").append(quote("embedding")).append(" ANN OF ?");
        }
        cql.append(" LIMIT ?");

        BoundStatementBuilder statement = session.prepare(cql.toString()).boundStatementBuilder();
        int index = 0;
        if (wrapper.isWithVector()) {
            statement.setVector(index++, vector(wrapper.getVector()), Float.class);
        }
        for (CassandraConditionPlanner.Predicate predicate : predicates) {
            CassandraMetadataType type = metadataColumns.get(predicate.getColumn());
            setPredicateValue(statement, index++, predicate.getValue(), type);
        }
        if (wrapper.isWithVector()) {
            statement.setVector(index++, vector(wrapper.getVector()), Float.class);
        }
        statement.setInt(index, limit);
        ResultSet rows = session.execute(statement
            .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_ONE)
            .setPageSize(limit)
            .setTimeout(Duration.ofMillis(config.getRequestTimeoutMillis()))
            .setIdempotence(true).build());

        List<Document> documents = new ArrayList<>();
        for (Row row : rows) {
            Document document = new Document();
            document.setId(row.getString("id"));
            document.setTitle(row.getString("title"));
            document.setContent(row.getString("content"));
            for (String column : selectedMetadata) {
                Object value = row.getObject(column);
                if (value instanceof Instant) {
                    value = Date.from((Instant) value);
                }
                if (value != null) {
                    document.putMetadata(column.substring(METADATA_PREFIX.length()), value);
                }
            }
            if (wrapper.isOutputVector()) {
                document.setVector(toArray(row.getVector("embedding", Float.class)));
            }
            if (wrapper.isWithVector()) {
                Float value = row.getFloat("score");
                document.setScore(value);
                if (wrapper.getMinScore() != null && (value == null || value < wrapper.getMinScore())) {
                    continue;
                }
            }
            documents.add(document);
        }
        return documents;
    }

    private void appendPredicates(
        StringBuilder cql,
        List<CassandraConditionPlanner.Predicate> predicates
    ) {
        if (predicates.isEmpty()) {
            return;
        }
        cql.append(" WHERE ");
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                cql.append(" AND ");
            }
            CassandraConditionPlanner.Predicate predicate = predicates.get(i);
            cql.append(quote(predicate.getColumn())).append(symbol(predicate.getType())).append('?');
        }
    }

    private String symbol(ConditionType type) {
        switch (type) {
            case EQ: return " = ";
            case GT: return " > ";
            case GE: return " >= ";
            case LT: return " < ";
            case LE: return " <= ";
            default: throw new IllegalArgumentException("Unsupported Cassandra condition: " + type);
        }
    }

    private void validatePredicates(
        List<CassandraConditionPlanner.Predicate> predicates,
        Map<String, CassandraMetadataType> metadataColumns
    ) {
        for (CassandraConditionPlanner.Predicate predicate : predicates) {
            String column = predicate.getColumn();
            if ("title".equals(column) || "content".equals(column)) {
                throw new IllegalArgumentException("Cassandra title/content filtering is not enabled; "
                    + "copy the value to a declared metadata field to create an SAI index.");
            }
            if (!"id".equals(column) && !metadataColumns.containsKey(column)) {
                throw new IllegalArgumentException("Cassandra metadata field does not exist: "
                    + column.substring(METADATA_PREFIX.length()));
            }
        }
    }

    private void ensureCollection(String collection, List<Document> documents) {
        validateIdentifier(collection, "collection");
        Object lock = schemaLocks.computeIfAbsent(collection, key -> new Object());
        synchronized (lock) {
            if (config.isAutoCreateCollection() && initializedCollections.add(collection)) {
                StringBuilder cql = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                    .append(table(collection)).append(" (")
                    .append(quote("id")).append(" text PRIMARY KEY, ")
                    .append(quote("title")).append(" text, ")
                    .append(quote("content")).append(" text, ")
                    .append(quote("embedding")).append(" vector<float, ")
                    .append(config.getVectorDimension()).append('>');
                for (Map.Entry<String, CassandraMetadataType> field
                    : config.getMetadataFieldTypes().entrySet()) {
                    cql.append(", ").append(quote(METADATA_PREFIX + field.getKey()))
                        .append(' ').append(field.getValue().getCqlType());
                }
                executeSchema(cql.append(')').toString());
                ensureCoreIndexes(collection);
                for (String field : config.getMetadataFieldTypes().keySet()) {
                    ensureScalarIndex(collection, METADATA_PREFIX + field);
                }
            }
            ensureInferredMetadata(collection, documents);
        }
    }

    private void ensureInferredMetadata(String collection, List<Document> documents) {
        Map<String, CassandraMetadataType> existing = metadataColumns(collection);
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadataMap();
            if (metadata == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                validateIdentifier(entry.getKey(), "metadata field");
                String column = METADATA_PREFIX + entry.getKey();
                CassandraMetadataType expected = entry.getValue() == null
                    ? config.getMetadataFieldTypes().get(entry.getKey())
                    : CassandraMetadataType.infer(entry.getValue());
                if (expected == null) {
                    continue;
                }
                CassandraMetadataType actual = existing.get(column);
                if (actual == null) {
                    if (!config.isAutoCreateMetadataColumns()) {
                        throw new IllegalArgumentException("Cassandra metadata field is not declared: " + entry.getKey());
                    }
                    executeSchema("ALTER TABLE " + table(collection) + " ADD " + quote(column)
                        + ' ' + expected.getCqlType());
                    ensureScalarIndex(collection, column);
                    existing.put(column, expected);
                } else if (actual != expected) {
                    throw new IllegalArgumentException("Cassandra metadata field " + entry.getKey()
                        + " expects " + actual + " but received " + expected + '.');
                }
            }
        }
    }

    private void ensureCoreIndexes(String collection) {
        executeSchema("CREATE CUSTOM INDEX IF NOT EXISTS " + quote(indexName(collection, "embedding"))
            + " ON " + table(collection) + " (" + quote("embedding") + ") USING 'StorageAttachedIndex'"
            + " WITH OPTIONS = {'similarity_function': '" + config.getSimilarity().getIndexValue() + "'}");
    }

    private void ensureScalarIndex(String collection, String column) {
        executeSchema("CREATE CUSTOM INDEX IF NOT EXISTS " + quote(indexName(collection, column))
            + " ON " + table(collection) + " (" + quote(column) + ") USING 'StorageAttachedIndex'");
    }

    private String indexName(String collection, String column) {
        String base = "af_" + collection + '_' + column + "_sai";
        if (base.length() <= 48) {
            return base;
        }
        return base.substring(0, 39) + '_' + Integer.toHexString(base.hashCode()).replace('-', '0');
    }

    private void executeSchema(String cql) {
        session.execute(SimpleStatement.builder(cql)
            .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_ONE)
            .setTimeout(Duration.ofMillis(config.getSchemaAgreementTimeoutMillis()))
            .setIdempotence(true).build());
        long deadline = System.currentTimeMillis() + config.getSchemaAgreementTimeoutMillis();
        while (!session.checkSchemaAgreement() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        if (!session.checkSchemaAgreement()) {
            throw new StoreException("Cassandra schema agreement timed out.");
        }
    }

    private Map<String, CassandraMetadataType> metadataColumns(String collection) {
        PreparedStatement prepared = session.prepare("SELECT column_name, type FROM system_schema.columns"
            + " WHERE keyspace_name = ? AND table_name = ?");
        ResultSet rows = session.execute(prepared.boundStatementBuilder()
            .setString(0, config.getKeyspace())
            .setString(1, collection)
            .setTimeout(Duration.ofMillis(config.getRequestTimeoutMillis()))
            .setIdempotence(true)
            .build());
        Map<String, CassandraMetadataType> result = new LinkedHashMap<>();
        for (Row row : rows) {
            String column = row.getString("column_name");
            if (column != null && column.startsWith(METADATA_PREFIX)) {
                result.put(column, metadataType(row.getString("type")));
            }
        }
        List<String> sorted = new ArrayList<>(result.keySet());
        Collections.sort(sorted);
        Map<String, CassandraMetadataType> ordered = new LinkedHashMap<>();
        for (String column : sorted) {
            ordered.put(column, result.get(column));
        }
        return ordered;
    }

    private CassandraMetadataType metadataType(String cqlType) {
        for (CassandraMetadataType value : CassandraMetadataType.values()) {
            if (value.getCqlType().equalsIgnoreCase(cqlType)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported Cassandra metadata CQL type: " + cqlType);
    }

    private String insertCql(String collection, Set<String> metadataColumns) {
        List<String> columns = new ArrayList<>();
        columns.add("id");
        columns.add("title");
        columns.add("content");
        columns.add("embedding");
        columns.addAll(metadataColumns);
        List<String> quoted = new ArrayList<>(columns.size());
        for (String column : columns) {
            quoted.add(quote(column));
        }
        return "INSERT INTO " + table(collection) + " (" + String.join(", ", quoted) + ") VALUES ("
            + String.join(", ", Collections.nCopies(columns.size(), "?")) + ')';
    }

    private List<String> selectedMetadata(SearchWrapper wrapper, Set<String> available) {
        if (wrapper.getOutputFields() == null) {
            return new ArrayList<>(available);
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String field : wrapper.getOutputFields()) {
            String column = CassandraConditionPlanner.normalizeField(field);
            if (available.contains(column)) {
                selected.add(column);
            }
        }
        return new ArrayList<>(selected);
    }

    private void validateDocument(Document document) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("Cassandra document and ID must not be null.");
        }
        validateVector(document.getVector());
    }

    private void validateVector(float[] value) {
        if (value == null || value.length != config.getVectorDimension()) {
            throw new IllegalArgumentException("Cassandra vector dimension must be "
                + config.getVectorDimension() + '.');
        }
    }

    private CqlVector<Float> vector(float[] value) {
        List<Float> values = new ArrayList<>(value.length);
        for (float item : value) {
            values.add(item);
        }
        return CqlVector.newInstance(values);
    }

    private float[] toArray(CqlVector<Float> value) {
        if (value == null) {
            return null;
        }
        float[] result = new float[value.size()];
        for (int i = 0; i < value.size(); i++) {
            result[i] = value.get(i);
        }
        return result;
    }

    private void setNullableString(BoundStatementBuilder statement, int index, String value) {
        if (value == null) {
            statement.setToNull(index);
        } else {
            statement.setString(index, value);
        }
    }

    private void setPredicateValue(
        BoundStatementBuilder statement,
        int index,
        Object value,
        CassandraMetadataType type
    ) {
        if (type == null) {
            statement.setString(index, String.valueOf(value));
        } else {
            setMetadataValue(statement, index, value, type);
        }
    }

    private void setMetadataValue(
        BoundStatementBuilder statement,
        int index,
        Object value,
        CassandraMetadataType type
    ) {
        if (value == null) {
            statement.setToNull(index);
            return;
        }
        switch (type) {
            case TEXT:
                statement.setString(index, String.valueOf(value));
                break;
            case INT:
                statement.setInt(index, ((Number) value).intValue());
                break;
            case BIGINT:
                statement.setLong(index, ((Number) value).longValue());
                break;
            case DOUBLE:
                statement.setDouble(index, ((Number) value).doubleValue());
                break;
            case BOOLEAN:
                statement.setBoolean(index, (Boolean) value);
                break;
            case TIMESTAMP:
                Instant instant = value instanceof Date
                    ? ((Date) value).toInstant() : (Instant) value;
                statement.setInstant(index, instant);
                break;
            default:
                throw new IllegalArgumentException("Unsupported Cassandra metadata type: " + type);
        }
    }

    private String resolveCollection(StoreOptions options) {
        StoreOptions resolved = options == null ? StoreOptions.DEFAULT : options;
        String collection = resolved.getCollectionNameOrDefault(config.getDefaultCollectionName());
        validateIdentifier(collection, "collection");
        return collection;
    }

    private String table(String collection) {
        return quote(config.getKeyspace()) + '.' + quote(collection);
    }

    static void validateIdentifier(String identifier, String label) {
        if (identifier == null || !identifier.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException("Cassandra " + label
                + " must start with a letter, contain only letters, digits or underscores, and be at most 48 characters.");
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static double score(Document document) {
        return document.getScore() == null ? Double.NEGATIVE_INFINITY : document.getScore();
    }

    @Override
    public void close() {
        session.close();
    }
}

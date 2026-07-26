/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.mongodb.atlas;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import com.mongodb.MongoCommandException;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.SearchIndexModel;
import com.mongodb.client.model.SearchIndexType;
import com.mongodb.client.model.WriteModel;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 MongoDB Atlas Vector Search 的文档向量存储。
 *
 * <p>每个 Agents-Flex Collection 映射为 MongoDB Collection，向量检索通过
 * {@code $vectorSearch} 聚合阶段执行。{@link SearchWrapper} 条件由
 * {@link MongoDBAtlasConditionBuilder} 转换为 BSON，并作为向量搜索预过滤条件发送到服务端。</p>
 *
 * <p>Atlas Vector Search 要求过滤字段预先写入向量索引定义。业务使用的过滤字段应配置在
 * {@link MongoDBAtlasVectorStoreConfig#getFilterFields()} 中，否则 Atlas 会拒绝该查询，而不是在
 * Java 客户端进行不完整的二次过滤。</p>
 */
public class MongoDBAtlasVectorStore extends DocumentStore implements AutoCloseable {

    static final String SCORE_FIELD = "__agentsflex_score";

    private final MongoDBAtlasVectorStoreConfig config;
    private final MongoClient client;
    private final MongoDatabase database;
    private final boolean ownsClient;
    private final MongoDBAtlasConditionBuilder conditionBuilder = new MongoDBAtlasConditionBuilder();
    private final Set<String> readyIndexes = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> indexLocks = new ConcurrentHashMap<>();

    public MongoDBAtlasVectorStore(MongoDBAtlasVectorStoreConfig config) {
        this(config, MongoClients.create(requireConfig(config).getConnectionString()), true);
        try {
            database.runCommand(new org.bson.Document("ping", 1));
        } catch (RuntimeException exception) {
            client.close();
            throw new StoreException("MongoDB Atlas connection failed: " + exception.getMessage(), exception);
        }
    }

    /**
     * 使用调用方管理的 MongoClient，适合应用统一连接池、测试或自定义 Driver 配置。
     */
    public MongoDBAtlasVectorStore(MongoDBAtlasVectorStoreConfig config, MongoClient client) {
        this(config, client, false);
    }

    private MongoDBAtlasVectorStore(
        MongoDBAtlasVectorStoreConfig config,
        MongoClient client,
        boolean ownsClient
    ) {
        this.config = requireConfig(config);
        this.client = Objects.requireNonNull(client, "MongoClient cannot be null");
        this.database = client.getDatabase(config.getDatabaseName());
        this.ownsClient = ownsClient;
        validateFilterFields(config.getFilterFields());
    }

    private static MongoDBAtlasVectorStoreConfig requireConfig(MongoDBAtlasVectorStoreConfig config) {
        Objects.requireNonNull(config, "MongoDBAtlasVectorStoreConfig cannot be null");
        if (!config.checkAvailable()) {
            throw new IllegalArgumentException("MongoDB Atlas vector store configuration is incomplete");
        }
        return config;
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        return upsert(documents, options, "Store");
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        return upsert(documents, options, "Update");
    }

    private StoreResult upsert(List<Document> documents, StoreOptions options, String operation) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        try {
            int dimension = validateDocuments(documents);
            String collectionName = collectionName(options);
            MongoCollection<org.bson.Document> collection = ensureCollection(collectionName);
            ensureVectorIndex(collection, vectorIndexName(options), dimension);

            List<WriteModel<org.bson.Document>> writes = new ArrayList<>();
            for (Document document : documents) {
                String id = String.valueOf(document.getId());
                writes.add(new ReplaceOneModel<>(Filters.eq("_id", id), storedDocument(document),
                    new ReplaceOptions().upsert(true)));
            }
            collection.bulkWrite(writes, new BulkWriteOptions().ordered(false));
            if (config.isWaitForSearchIndexing()) {
                waitUntilDocumentsVisible(collection, vectorIndexName(options), documents);
            }
            return StoreResult.successWithIds(documents);
        } catch (Exception exception) {
            return StoreResult.fail(operation + " failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        try {
            List<String> normalizedIds = new ArrayList<>();
            for (Object id : ids) {
                if (id == null) {
                    throw new IllegalArgumentException("MongoDB document ID cannot be null");
                }
                normalizedIds.add(String.valueOf(id));
            }
            MongoCollection<org.bson.Document> collection = database.getCollection(collectionName(options));
            List<org.bson.Document> deletedSources = new ArrayList<>();
            if (config.isWaitForSearchIndexing()) {
                collection.find(Filters.in("_id", normalizedIds)).into(deletedSources);
            }
            collection.deleteMany(Filters.in("_id", normalizedIds));
            String indexName = vectorIndexName(options);
            if (!deletedSources.isEmpty() && hasSearchIndex(collection, indexName)) {
                waitUntilDocumentsAbsent(collection, indexName, deletedSources);
            }
            return StoreResult.success();
        } catch (Exception exception) {
            return StoreResult.fail("Delete failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        Objects.requireNonNull(wrapper, "SearchWrapper cannot be null");
        try {
            MongoCollection<org.bson.Document> collection = database.getCollection(collectionName(options));
            BsonDocument filter = conditionBuilder.build(wrapper.getCondition());
            if (!wrapper.isWithVector()) {
                return filterOnlySearch(collection, filter, wrapper);
            }

            float[] vector = wrapper.getVector();
            if (vector == null || vector.length == 0) {
                throw new StoreException(
                    "MongoDB Atlas vector query requires a vector; use withVector(false) for filter-only queries");
            }
            ensureVectorIndex(collection, vectorIndexName(options), vector.length);
            return vectorSearch(collection, vectorIndexName(options), filter, wrapper);
        } catch (StoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StoreException("MongoDB Atlas search failed: " + exception.getMessage(), exception);
        }
    }

    private List<Document> vectorSearch(
        MongoCollection<org.bson.Document> collection,
        String indexName,
        BsonDocument filter,
        SearchWrapper wrapper
    ) {
        int limit = wrapper.getMaxResults();
        int numCandidates = candidateCount(limit);
        org.bson.Document vectorSearch = new org.bson.Document("index", indexName)
            .append("path", config.getVectorField())
            .append("queryVector", vectorValues(wrapper.getVector()))
            .append("numCandidates", numCandidates)
            .append("limit", limit);
        if (!filter.isEmpty()) {
            vectorSearch.append("filter", filter);
        }

        List<Bson> pipeline = new ArrayList<>();
        pipeline.add(new org.bson.Document("$vectorSearch", vectorSearch));
        pipeline.add(new org.bson.Document("$set",
            new org.bson.Document(SCORE_FIELD, new org.bson.Document("$meta", "vectorSearchScore"))));
        if (wrapper.getMinScore() != null) {
            pipeline.add(new org.bson.Document("$match",
                new org.bson.Document(SCORE_FIELD, new org.bson.Document("$gte", wrapper.getMinScore()))));
        }
        Bson projection = projection(wrapper, true);
        if (projection != null) {
            pipeline.add(new org.bson.Document("$project", projection));
        }

        List<Document> results = new ArrayList<>();
        AggregateIterable<org.bson.Document> iterable = collection.aggregate(pipeline);
        for (org.bson.Document source : iterable) {
            results.add(toDocument(source, wrapper.isOutputVector(), true));
        }
        return results;
    }

    private List<Document> filterOnlySearch(
        MongoCollection<org.bson.Document> collection,
        BsonDocument filter,
        SearchWrapper wrapper
    ) {
        FindIterable<org.bson.Document> iterable = collection.find(filter).limit(wrapper.getMaxResults());
        Bson projection = projection(wrapper, false);
        if (projection != null) {
            iterable = iterable.projection(projection);
        }
        List<Document> results = new ArrayList<>();
        for (org.bson.Document source : iterable) {
            results.add(toDocument(source, wrapper.isOutputVector(), false));
        }
        return results;
    }

    /** 生成 MongoDB projection；返回 null 表示保留所有字段。 */
    private Bson projection(SearchWrapper wrapper, boolean includeScore) {
        if (wrapper.getOutputFields() == null) {
            if (!wrapper.isOutputVector()) {
                return Projections.exclude(config.getVectorField());
            }
            return null;
        }

        LinkedHashSet<String> fields = new LinkedHashSet<>();
        Collections.addAll(fields, "_id", "id", "title", "content");
        if (includeScore) {
            fields.add(SCORE_FIELD);
        }
        for (String outputField : wrapper.getOutputFields()) {
            fields.add(normalizeOutputField(outputField));
        }
        if (wrapper.isOutputVector()) {
            fields.add(config.getVectorField());
        } else {
            fields.remove(config.getVectorField());
        }
        return Projections.include(new ArrayList<>(fields));
    }

    private String normalizeOutputField(String field) {
        String normalized = field.trim();
        if ("vector".equals(normalized)) {
            return config.getVectorField();
        }
        return MongoDBAtlasConditionBuilder.normalizeField(normalized);
    }

    private org.bson.Document storedDocument(Document document) {
        org.bson.Document stored = new org.bson.Document("_id", String.valueOf(document.getId()))
            .append("id", String.valueOf(document.getId()))
            .append("title", document.getTitle())
            .append("content", document.getContent())
            .append(config.getVectorField(), vectorValues(document.getVector()));
        Map<String, Object> metadata = document.getMetadataMap();
        stored.append("metadataMap", metadata == null
            ? new org.bson.Document() : new org.bson.Document(metadata));
        return stored;
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(org.bson.Document source, boolean outputVector, boolean withScore) {
        Document document = new Document();
        document.setId(source.get("id", source.get("_id")));
        document.setTitle(source.getString("title"));
        document.setContent(source.getString("content"));
        Object metadata = source.get("metadataMap");
        if (metadata instanceof Map<?, ?>) {
            document.setMetadataMap((Map<String, Object>) metadata);
        }
        if (outputVector) {
            Object vector = source.get(config.getVectorField());
            if (vector instanceof List<?>) {
                document.setVectorByNumbers((List<? extends Number>) vector);
            }
        }
        if (withScore) {
            Number score = source.get(SCORE_FIELD, Number.class);
            document.setScore(score == null ? null : score.floatValue());
        }
        return document;
    }

    private int validateDocuments(List<Document> documents) {
        Integer dimension = null;
        for (Document document : documents) {
            if (document == null || document.getId() == null) {
                throw new IllegalArgumentException("MongoDB documents and IDs cannot be null");
            }
            float[] vector = document.getVector();
            if (vector == null || vector.length == 0) {
                throw new IllegalArgumentException("MongoDB document vectors cannot be empty");
            }
            if (dimension == null) {
                dimension = vector.length;
            } else if (dimension != vector.length) {
                throw new IllegalArgumentException("All MongoDB document vectors must have the same dimension");
            }
        }
        if (config.getVectorDimension() > 0 && dimension != config.getVectorDimension()) {
            throw new IllegalArgumentException("MongoDB vector dimension does not match configured dimension "
                + config.getVectorDimension());
        }
        return dimension;
    }

    private MongoCollection<org.bson.Document> ensureCollection(String collectionName) {
        boolean exists = false;
        for (String existing : database.listCollectionNames()) {
            if (collectionName.equals(existing)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            if (!config.isAutoCreateCollection()) {
                throw new StoreException("MongoDB collection does not exist: " + collectionName);
            }
            try {
                database.createCollection(collectionName);
            } catch (MongoCommandException exception) {
                if (exception.getErrorCode() != 48) {
                    throw exception;
                }
            }
        }
        return database.getCollection(collectionName);
    }

    private void ensureVectorIndex(
        MongoCollection<org.bson.Document> collection,
        String indexName,
        int dimension
    ) {
        String cacheKey = collection.getNamespace().getFullName() + "/" + indexName + "/" + dimension;
        if (readyIndexes.contains(cacheKey)) {
            return;
        }
        Object lock = indexLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            if (readyIndexes.contains(cacheKey)) {
                return;
            }
            org.bson.Document existing = collection.listSearchIndexes().name(indexName).first();
            if (existing == null) {
                if (!config.isAutoCreateVectorIndex()) {
                    throw new StoreException("MongoDB Atlas vector index does not exist: " + indexName);
                }
                collection.createSearchIndexes(Collections.singletonList(new SearchIndexModel(
                    indexName, vectorIndexDefinition(dimension), SearchIndexType.vectorSearch())));
            }
            waitUntilIndexReady(collection, indexName);
            readyIndexes.add(cacheKey);
            indexLocks.remove(cacheKey);
        }
    }

    private org.bson.Document vectorIndexDefinition(int dimension) {
        List<org.bson.Document> fields = new ArrayList<>();
        fields.add(new org.bson.Document("type", "vector")
            .append("path", config.getVectorField())
            .append("numDimensions", dimension)
            .append("similarity", config.getSimilarity().getAtlasValue()));

        Set<String> uniqueFilters = new HashSet<>();
        uniqueFilters.add("id");
        for (String configuredField : config.getFilterFields()) {
            uniqueFilters.add(MongoDBAtlasConditionBuilder.normalizeField(configuredField));
        }
        for (String filterField : uniqueFilters) {
            fields.add(new org.bson.Document("type", "filter").append("path", filterField));
        }
        return new org.bson.Document("fields", fields);
    }

    private void waitUntilIndexReady(MongoCollection<org.bson.Document> collection, String indexName) {
        long deadline = System.currentTimeMillis() + config.getIndexReadyTimeoutMillis();
        RuntimeException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                org.bson.Document index = collection.listSearchIndexes().name(indexName).first();
                if (isIndexReady(index)) {
                    return;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new StoreException("Interrupted while waiting for MongoDB Atlas vector index", exception);
            }
        }
        throw new StoreException("Timed out waiting for MongoDB Atlas vector index: " + indexName, lastFailure);
    }

    /** 等待每个文档的新版本已经进入 mongot，而不仅是写入 mongod。 */
    private void waitUntilDocumentsVisible(
        MongoCollection<org.bson.Document> collection,
        String indexName,
        List<Document> documents
    ) {
        long deadline = System.currentTimeMillis() + config.getIndexReadyTimeoutMillis();
        Set<String> pendingIds = new HashSet<>();
        Map<String, org.bson.Document> expectedById = new java.util.HashMap<>();
        for (Document document : documents) {
            String id = String.valueOf(document.getId());
            pendingIds.add(id);
            expectedById.put(id, storedDocument(document));
        }
        RuntimeException lastFailure = null;
        while (!pendingIds.isEmpty() && System.currentTimeMillis() < deadline) {
            for (String id : new ArrayList<>(pendingIds)) {
                org.bson.Document expected = expectedById.get(id);
                try {
                    org.bson.Document indexed = searchById(collection, indexName, expected);
                    if (sameStoredDocument(expected, indexed)) {
                        pendingIds.remove(id);
                    }
                } catch (RuntimeException exception) {
                    lastFailure = exception;
                }
            }
            if (!pendingIds.isEmpty()) {
                sleepForIndexing();
            }
        }
        if (!pendingIds.isEmpty()) {
            throw new StoreException("Timed out waiting for MongoDB Atlas documents to become searchable: "
                + pendingIds, lastFailure);
        }
    }

    /** 等待已删除文档从 mongot 中消失，保证删除返回后的向量查询不会读到旧版本。 */
    private void waitUntilDocumentsAbsent(
        MongoCollection<org.bson.Document> collection,
        String indexName,
        List<org.bson.Document> deletedSources
    ) {
        long deadline = System.currentTimeMillis() + config.getIndexReadyTimeoutMillis();
        Set<String> pendingIds = new HashSet<>();
        Map<String, org.bson.Document> sourceById = new java.util.HashMap<>();
        for (org.bson.Document source : deletedSources) {
            String id = source.getString("id");
            pendingIds.add(id);
            sourceById.put(id, source);
        }
        RuntimeException lastFailure = null;
        while (!pendingIds.isEmpty() && System.currentTimeMillis() < deadline) {
            for (String id : new ArrayList<>(pendingIds)) {
                try {
                    if (searchById(collection, indexName, sourceById.get(id)) == null) {
                        pendingIds.remove(id);
                    }
                } catch (RuntimeException exception) {
                    lastFailure = exception;
                }
            }
            if (!pendingIds.isEmpty()) {
                sleepForIndexing();
            }
        }
        if (!pendingIds.isEmpty()) {
            throw new StoreException("Timed out waiting for MongoDB Atlas documents to leave search index: "
                + pendingIds, lastFailure);
        }
    }

    private org.bson.Document searchById(
        MongoCollection<org.bson.Document> collection,
        String indexName,
        org.bson.Document source
    ) {
        Object vectorValue = source.get(config.getVectorField());
        if (!(vectorValue instanceof List<?>)) {
            throw new StoreException("Stored MongoDB document has no vector: " + source.get("id"));
        }
        org.bson.Document vectorSearch = new org.bson.Document("index", indexName)
            .append("path", config.getVectorField())
            .append("queryVector", vectorValue)
            .append("numCandidates", Math.max(10, config.getNumCandidatesMultiplier()))
            .append("limit", 1)
            .append("filter", visibilityFilter(source));
        return collection.aggregate(Collections.singletonList(
            new org.bson.Document("$vectorSearch", vectorSearch))).first();
    }

    /**
     * 使用 ID 和当前全部已配置过滤字段探测可见性，确保 mongot 的向量内容与过滤倒排结构均已更新。
     */
    private org.bson.Document visibilityFilter(org.bson.Document source) {
        org.bson.Document filter = new org.bson.Document("id", source.getString("id"));
        for (String configuredField : config.getFilterFields()) {
            String field = MongoDBAtlasConditionBuilder.normalizeField(configuredField);
            Object value = valueAtPath(source, field);
            if (value != null) {
                filter.append(field, value);
            }
        }
        return filter;
    }

    private Object valueAtPath(org.bson.Document source, String path) {
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?>)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(part);
        }
        return current;
    }

    private boolean sameStoredDocument(org.bson.Document expected, org.bson.Document actual) {
        if (actual == null) {
            return false;
        }
        return Objects.equals(expected.get("id"), actual.get("id"))
            && Objects.equals(expected.get("title"), actual.get("title"))
            && Objects.equals(expected.get("content"), actual.get("content"))
            && Objects.equals(expected.get(config.getVectorField()), actual.get(config.getVectorField()))
            && Objects.equals(expected.get("metadataMap"), actual.get("metadataMap"));
    }

    private boolean hasSearchIndex(MongoCollection<org.bson.Document> collection, String indexName) {
        return collection.listSearchIndexes().name(indexName).first() != null;
    }

    private void sleepForIndexing() {
        try {
            Thread.sleep(250L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StoreException("Interrupted while waiting for MongoDB Atlas search indexing", exception);
        }
    }

    private boolean isIndexReady(org.bson.Document index) {
        if (index == null) {
            return false;
        }
        if (Boolean.TRUE.equals(index.getBoolean("queryable"))) {
            return true;
        }
        String status = index.getString("status");
        return "READY".equalsIgnoreCase(status) || "STEADY".equalsIgnoreCase(status);
    }

    private int candidateCount(int limit) {
        long candidates = (long) limit * config.getNumCandidatesMultiplier();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(limit, candidates));
    }

    private List<Double> vectorValues(float[] vector) {
        List<Double> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("MongoDB vectors must contain only finite numbers");
            }
            values.add((double) value);
        }
        return values;
    }

    private String collectionName(StoreOptions options) {
        String collectionName = options.getCollectionNameOrDefault(config.getDefaultCollectionName());
        if (!StringUtil.hasText(collectionName)) {
            throw new IllegalArgumentException("MongoDB collection name cannot be blank");
        }
        return collectionName;
    }

    private String vectorIndexName(StoreOptions options) {
        String indexName = options.getIndexNameOrDefault(config.getVectorIndexName());
        if (!StringUtil.hasText(indexName)) {
            throw new IllegalArgumentException("MongoDB vector index name cannot be blank");
        }
        return indexName;
    }

    private void validateFilterFields(List<String> filterFields) {
        for (String filterField : filterFields) {
            MongoDBAtlasConditionBuilder.normalizeField(filterField);
        }
    }

    MongoClient getClient() {
        return client;
    }

    MongoDatabase getDatabase() {
        return database;
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }
}

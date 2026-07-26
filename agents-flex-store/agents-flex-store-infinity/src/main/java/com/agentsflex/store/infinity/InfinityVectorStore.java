/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.infinity;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.OkHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.URI;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Infinity HTTP API 的文档向量存储。
 *
 * <p>{@link StoreOptions#getCollectionName()} 映射为 Infinity table，database 由配置统一指定。
 * 文档固定列为 id、title、content、embedding，metadata 映射成 {@code metadata_字段名} 标量列，
 * SearchWrapper 条件会转换成 Infinity filter 并在服务端与向量检索共同执行。</p>
 *
 * <p>Infinity 的普通 INSERT 不具备 upsert 语义，即使声明 primary key 也可能保留重复 ID。
 * 因此 store/update 会先按业务 ID 删除旧行，再批量插入新行。该过程不是跨请求事务，调用方需要在
 * 生产环境结合重试和上游幂等控制处理极端网络中断。</p>
 */
public class InfinityVectorStore extends DocumentStore implements AutoCloseable {
    static final String ID_FIELD = "id";
    static final String TITLE_FIELD = "title";
    static final String CONTENT_FIELD = "content";
    static final String VECTOR_FIELD = "embedding";
    static final String METADATA_PREFIX = "metadata_";
    private static final String VECTOR_INDEX = "embedding_hnsw";
    private static final String IDENTIFIER_PATTERN = "[A-Za-z][A-Za-z0-9_]{0,63}";
    private static final int MAX_INSERT_BATCH = 8192;

    private final InfinityVectorStoreConfig config;
    private final String baseUrl;
    private final Map<String, String> headers;
    private final AgentsFlexHttpClient httpClient;
    private final CloseableHttpClient queryHttpClient;
    private final InfinityExpressionAdaptor expressionAdaptor = new InfinityExpressionAdaptor();
    private final Map<String, Object> schemaLocks = new ConcurrentHashMap<>();
    /** 同一 Store 实例内串行化每个 Collection 的 delete+insert，避免并发覆盖产生重复 ID。 */
    private final Map<String, Object> writeLocks = new ConcurrentHashMap<>();

    public InfinityVectorStore(InfinityVectorStoreConfig config) {
        this.config = requireConfig(config);
        validateIdentifier(config.getDatabaseName(), "database");
        validateIdentifier(config.getDefaultCollectionName(), "collection");
        for (Map.Entry<String, InfinityMetadataType> entry : config.getMetadataFieldTypes().entrySet()) {
            metadataField(entry.getKey());
            Objects.requireNonNull(entry.getValue(), "Infinity metadata type cannot be null");
        }
        URI uri = config.serverUri();
        String configured = uri.toString();
        this.baseUrl = configured.endsWith("/")
            ? configured.substring(0, configured.length() - 1) : configured;
        this.headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        if (StringUtil.hasText(config.getApiKey())) {
            headers.put("Authorization", "Bearer " + config.getApiKey().trim());
        }
        long timeout = config.getRequestTimeoutMillis();
        this.httpClient = new AgentsFlexHttpClient(new OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build());
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(timeout))
            .setResponseTimeout(Timeout.ofMilliseconds(timeout))
            .build();
        this.queryHttpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
        initializeDatabase();
    }

    private static InfinityVectorStoreConfig requireConfig(InfinityVectorStoreConfig config) {
        if (config == null || !config.checkAvailable()) {
            throw new IllegalArgumentException("Infinity vector store configuration is incomplete");
        }
        return config;
    }

    private void initializeDatabase() {
        JSONObject databases = get("/databases");
        boolean exists = databases.getJSONArray("databases").contains(config.getDatabaseName());
        if (!exists && !config.isAutoCreateDatabase()) {
            throw new StoreException("Infinity database does not exist: " + config.getDatabaseName());
        }
        if (!exists) {
            JSONObject body = new JSONObject();
            body.put("create_option", "ignore_if_exists");
            post("/databases/" + config.getDatabaseName(), body);
        }
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        return write(documents, options, "store");
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        // 删除后插入可确保整份 metadata 被覆盖，不会残留上一次写入但本次已移除的字段。
        return write(documents, options, "update");
    }

    private StoreResult write(List<Document> documents, StoreOptions options, String operation) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        try {
            for (Document document : documents) {
                validateDocument(document);
            }
            String collection = collection(options);
            Map<String, InfinityMetadataType> schema = ensureCollection(collection, documents);
            Object writeLock = writeLocks.computeIfAbsent(collection, key -> new Object());
            synchronized (writeLock) {
                deleteIds(collection, documentIds(documents));
                for (int start = 0; start < documents.size(); start += MAX_INSERT_BATCH) {
                    int end = Math.min(documents.size(), start + MAX_INSERT_BATCH);
                    JSONArray rows = new JSONArray(end - start);
                    for (int i = start; i < end; i++) {
                        rows.add(toRow(documents.get(i), schema));
                    }
                    post("/databases/" + config.getDatabaseName() + "/tables/" + collection + "/docs", rows);
                }
            }
            return StoreResult.successWithIds(documents);
        } catch (Exception exception) {
            return StoreResult.fail("Infinity " + operation + " failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        try {
            String collection = collection(options);
            ensureCollection(collection, Collections.emptyList());
            Object writeLock = writeLocks.computeIfAbsent(collection, key -> new Object());
            synchronized (writeLock) {
                deleteIds(collection, ids);
            }
            return StoreResult.success();
        } catch (Exception exception) {
            return StoreResult.fail("Infinity delete failed: " + exception.getMessage(), exception);
        }
    }

    private void deleteIds(String collection, Collection<?> ids) {
        StringJoiner filter = new StringJoiner(", ", ID_FIELD + " IN (", ")");
        for (Object id : ids) {
            if (id == null) {
                throw new IllegalArgumentException("Infinity document ID cannot be null");
            }
            filter.add(InfinityExpressionAdaptor.literal(String.valueOf(id)));
        }
        JSONObject body = new JSONObject();
        body.put("filter", filter.toString());
        delete("/databases/" + config.getDatabaseName() + "/tables/" + collection + "/docs", body);
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        Objects.requireNonNull(wrapper, "SearchWrapper cannot be null").validate();
        String collection = collection(options);
        Map<String, InfinityMetadataType> schema = ensureCollection(collection, Collections.emptyList());
        if (wrapper.isWithVector()) {
            validateVector(wrapper.getVector());
        }

        JSONObject request = new JSONObject();
        request.put("output", outputFields(wrapper, schema));
        request.put("limit", String.valueOf(wrapper.getMaxResults()));
        if (wrapper.getCondition() != null) {
            request.put("filter", wrapper.getCondition().toExpression(expressionAdaptor));
        }
        if (wrapper.isWithVector()) {
            JSONObject dense = new JSONObject();
            dense.put("match_method", "dense");
            dense.put("fields", VECTOR_FIELD);
            dense.put("query_vector", doubles(wrapper.getVector()));
            dense.put("element_type", "float");
            dense.put("metric_type", config.getSimilarity().getMetricName());
            dense.put("topn", wrapper.getMaxResults());
            request.put("search", Collections.singletonList(dense));
        }

        JSONObject response = query("/databases/" + config.getDatabaseName()
            + "/tables/" + collection + "/docs", request);
        List<Document> result = parseDocuments(response.getJSONArray("output"), wrapper);
        if (wrapper.getMinScore() != null) {
            result.removeIf(document -> document.getScore() == null
                || document.getScore() < wrapper.getMinScore().floatValue());
        }
        return result;
    }

    private JSONArray outputFields(SearchWrapper wrapper, Map<String, InfinityMetadataType> schema) {
        Set<String> output = new LinkedHashSet<>();
        Collections.addAll(output, ID_FIELD, TITLE_FIELD, CONTENT_FIELD);
        if (wrapper.getOutputFields() == null) {
            for (String column : schema.keySet()) {
                output.add(column);
            }
        } else {
            for (String requested : wrapper.getOutputFields()) {
                String field = normalizeField(requested);
                if (field.startsWith(METADATA_PREFIX) && !schema.containsKey(field)) {
                    throw new IllegalArgumentException("Infinity metadata column does not exist: " + requested);
                }
                output.add(field);
            }
        }
        if (wrapper.isOutputVector()) {
            output.add(VECTOR_FIELD);
        }
        if (wrapper.isWithVector()) {
            output.add("_" + config.getSimilarity().getResultField().toLowerCase());
        }
        return new JSONArray(output);
    }

    private List<Document> parseDocuments(JSONArray rows, SearchWrapper wrapper) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> result = new ArrayList<>(rows.size());
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            JSONArray cells = rows.getJSONArray(rowIndex);
            Map<String, Object> values = new LinkedHashMap<>();
            for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
                JSONObject cell = cells.getJSONObject(cellIndex);
                for (Map.Entry<String, Object> entry : cell.entrySet()) {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
            Document document = new Document();
            document.setId(nullValue(values.get(ID_FIELD)));
            document.setTitle(stringValue(values.get(TITLE_FIELD)));
            document.setContent(stringValue(values.get(CONTENT_FIELD)));
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getKey().startsWith(METADATA_PREFIX)) {
                    Object metadataValue = nullValue(entry.getValue());
                    // Metadata 内部使用 ConcurrentHashMap，不能保存 null。字段为 Infinity NULL 时保持缺失，
                    // getMetadata() 仍返回 null，并且不会把服务端的 "Null" 哨兵暴露给业务代码。
                    if (metadataValue != null) {
                        document.putMetadata(entry.getKey().substring(METADATA_PREFIX.length()), metadataValue);
                    }
                }
            }
            if (wrapper.isOutputVector()) {
                document.setVector(parseVector(values.get(VECTOR_FIELD)));
            }
            if (wrapper.isWithVector()) {
                Number raw = number(values.get(config.getSimilarity().getResultField()));
                if (raw != null) {
                    float score = raw.floatValue();
                    if (config.getSimilarity() == InfinitySimilarity.L2) {
                        score = 1.0f / (1.0f + Math.max(0.0f, score));
                    }
                    document.setScore(score);
                }
            }
            result.add(document);
        }
        return result;
    }

    private Map<String, InfinityMetadataType> ensureCollection(String collection, List<Document> documents) {
        validateIdentifier(collection, "collection");
        Object lock = schemaLocks.computeIfAbsent(collection, key -> new Object());
        synchronized (lock) {
            boolean exists = tableNames().contains(collection);
            Map<String, InfinityMetadataType> desired = desiredMetadataSchema(documents);
            if (!exists) {
                if (!config.isAutoCreateCollection()) {
                    throw new StoreException("Infinity collection does not exist: " + collection);
                }
                createTable(collection, desired);
            }
            Map<String, InfinityMetadataType> actual = readMetadataSchema(collection);
            Map<String, InfinityMetadataType> missing = new LinkedHashMap<>();
            for (Map.Entry<String, InfinityMetadataType> entry : desired.entrySet()) {
                InfinityMetadataType current = actual.get(entry.getKey());
                if (current == null) {
                    missing.put(entry.getKey(), entry.getValue());
                } else if (current != entry.getValue()) {
                    throw new StoreException("Infinity metadata type mismatch for " + entry.getKey()
                        + ": expected " + entry.getValue() + " but found " + current);
                }
            }
            if (!missing.isEmpty()) {
                if (!config.isAutoCreateMetadataColumns()) {
                    throw new StoreException("Infinity metadata columns do not exist: " + missing.keySet());
                }
                addColumns(collection, missing);
                actual.putAll(missing);
            }
            ensureVectorIndex(collection);
            return actual;
        }
    }

    private void createTable(String collection, Map<String, InfinityMetadataType> metadata) {
        JSONArray fields = new JSONArray();
        fields.add(field(ID_FIELD, "varchar", true));
        fields.add(field(TITLE_FIELD, "varchar", false));
        fields.add(field(CONTENT_FIELD, "varchar", false));
        fields.add(field(VECTOR_FIELD, "vector," + config.getVectorDimension() + ",float", false));
        for (Map.Entry<String, InfinityMetadataType> entry : metadata.entrySet()) {
            fields.add(field(entry.getKey(), entry.getValue().getColumnType(), false));
        }
        JSONObject body = new JSONObject();
        body.put("create_option", "ignore_if_exists");
        body.put("fields", fields);
        post("/databases/" + config.getDatabaseName() + "/tables/" + collection, body);
    }

    private JSONObject field(String name, String type, boolean primaryKey) {
        JSONObject field = new JSONObject();
        field.put("name", name);
        field.put("type", type);
        if (primaryKey) {
            field.put("constraints", java.util.Arrays.asList("primary key", "not null"));
        } else {
            // Infinity HTTP 插入只有在 DDL 显式携带 default=Null 时才允许省略可空列。
            field.put("default", "Null");
        }
        return field;
    }

    private void addColumns(String collection, Map<String, InfinityMetadataType> missing) {
        JSONArray fields = new JSONArray();
        for (Map.Entry<String, InfinityMetadataType> entry : missing.entrySet()) {
            fields.add(field(entry.getKey(), entry.getValue().getColumnType(), false));
        }
        JSONObject body = new JSONObject();
        body.put("fields", fields);
        post("/databases/" + config.getDatabaseName() + "/tables/" + collection + "/columns", body);
    }

    private void ensureVectorIndex(String collection) {
        if (!config.isAutoCreateVectorIndex()) {
            return;
        }
        JSONObject indexes = get("/databases/" + config.getDatabaseName()
            + "/tables/" + collection + "/indexes");
        JSONArray values = indexes.getJSONArray("indexes");
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (VECTOR_INDEX.equals(values.getJSONObject(i).getString("index_name"))) {
                    return;
                }
            }
        }
        JSONObject index = new JSONObject();
        index.put("type", "Hnsw");
        index.put("M", String.valueOf(config.getHnswM()));
        index.put("ef_construction", String.valueOf(config.getHnswEfConstruction()));
        index.put("metric", config.getSimilarity().getMetricName());
        JSONObject body = new JSONObject();
        body.put("fields", Collections.singletonList(VECTOR_FIELD));
        body.put("index", index);
        body.put("create_option", "ignore_if_exists");
        post("/databases/" + config.getDatabaseName() + "/tables/" + collection
            + "/indexes/" + VECTOR_INDEX, body);
    }

    private Set<String> tableNames() {
        JSONObject response = get("/databases/" + config.getDatabaseName() + "/tables");
        JSONArray names = response.getJSONArray("table_names");
        Set<String> result = new LinkedHashSet<>();
        if (names != null) {
            for (Object name : names) {
                result.add(String.valueOf(name));
            }
        }
        return result;
    }

    private Map<String, InfinityMetadataType> readMetadataSchema(String collection) {
        JSONObject response = get("/databases/" + config.getDatabaseName()
            + "/tables/" + collection + "/columns");
        Map<String, InfinityMetadataType> result = new LinkedHashMap<>();
        JSONArray columns = response.getJSONArray("columns");
        for (int i = 0; columns != null && i < columns.size(); i++) {
            JSONObject column = columns.getJSONObject(i);
            String name = column.getString("name");
            if (VECTOR_FIELD.equals(name)) {
                String type = column.getString("type");
                if (!type.equalsIgnoreCase("Embedding(float," + config.getVectorDimension() + ")")) {
                    throw new StoreException("Infinity vector dimension mismatch: " + type);
                }
            } else if (name.startsWith(METADATA_PREFIX)) {
                result.put(name, metadataType(column.getString("type")));
            }
        }
        return result;
    }

    private Map<String, InfinityMetadataType> desiredMetadataSchema(List<Document> documents) {
        Map<String, InfinityMetadataType> result = new LinkedHashMap<>();
        for (Map.Entry<String, InfinityMetadataType> entry : config.getMetadataFieldTypes().entrySet()) {
            result.put(metadataField(entry.getKey()), entry.getValue());
        }
        for (Document document : documents) {
            if (document.getMetadataMap() == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : document.getMetadataMap().entrySet()) {
                String field = metadataField(entry.getKey());
                if (entry.getValue() != null) {
                    InfinityMetadataType inferred = inferType(entry.getValue());
                    InfinityMetadataType previous = result.putIfAbsent(field, inferred);
                    if (previous != null && previous != inferred) {
                        throw new IllegalArgumentException("Conflicting Infinity metadata types for " + entry.getKey());
                    }
                }
            }
        }
        return result;
    }

    private JSONObject toRow(Document document, Map<String, InfinityMetadataType> schema) {
        JSONObject row = new JSONObject();
        row.put(ID_FIELD, String.valueOf(document.getId()));
        if (document.getTitle() != null) row.put(TITLE_FIELD, document.getTitle());
        if (document.getContent() != null) row.put(CONTENT_FIELD, document.getContent());
        row.put(VECTOR_FIELD, doubles(document.getVector()));
        if (document.getMetadataMap() != null) {
            for (Map.Entry<String, Object> entry : document.getMetadataMap().entrySet()) {
                String field = metadataField(entry.getKey());
                if (entry.getValue() != null) {
                    InfinityMetadataType type = schema.get(field);
                    if (type == null) {
                        throw new IllegalArgumentException("Infinity metadata column does not exist: " + entry.getKey());
                    }
                    row.put(field, normalizeMetadataValue(entry.getValue(), type));
                }
            }
        }
        return row;
    }

    private void validateDocument(Document document) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("Infinity document and document ID cannot be null");
        }
        validateVector(document.getVector());
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length != config.getVectorDimension()) {
            throw new IllegalArgumentException("Infinity vector dimension must be " + config.getVectorDimension());
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Infinity vector values must be finite");
            }
        }
    }

    private String collection(StoreOptions options) {
        String name = options == null ? config.getDefaultCollectionName()
            : options.getCollectionNameOrDefault(config.getDefaultCollectionName());
        validateIdentifier(name, "collection");
        return name;
    }

    static String normalizeField(String field) {
        String normalized = field == null ? "" : field.trim();
        if (ID_FIELD.equals(normalized) || TITLE_FIELD.equals(normalized) || CONTENT_FIELD.equals(normalized)) {
            return normalized;
        }
        if (normalized.startsWith("metadataMap.")) {
            normalized = normalized.substring("metadataMap.".length());
        } else if (normalized.startsWith("metadata.")) {
            normalized = normalized.substring("metadata.".length());
        }
        return metadataField(normalized);
    }

    static String metadataField(String field) {
        String normalized = field == null ? "" : field.trim();
        if (normalized.startsWith(METADATA_PREFIX)) {
            normalized = normalized.substring(METADATA_PREFIX.length());
        }
        validateIdentifier(normalized, "metadata field");
        return METADATA_PREFIX + normalized;
    }

    static void validateIdentifier(String name, String label) {
        if (name == null || !name.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException("Invalid Infinity " + label + ": " + name);
        }
    }

    private JSONObject get(String path) { return parse(httpClient.get(baseUrl + path, headers)); }
    private JSONObject post(String path, Object body) {
        return parse(httpClient.post(baseUrl + path, headers, JSON.toJSONString(body)));
    }
    private JSONObject delete(String path, Object body) {
        return parse(httpClient.delete(baseUrl + path, headers, JSON.toJSONString(body)));
    }

    /** Infinity 查询协议固定为带 JSON body 的 GET，因此不能使用会拒绝该形式的 OkHttp。 */
    private JSONObject query(String path, JSONObject body) {
        HttpUriRequestBase request = new HttpUriRequestBase("GET", URI.create(baseUrl + path));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.addHeader(header.getKey(), header.getValue());
        }
        request.setEntity(new StringEntity(body.toJSONString(), ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = queryHttpClient.execute(request)) {
            String content = response.getEntity() == null ? "{}" : EntityUtils.toString(response.getEntity());
            if (response.getCode() != 200) {
                throw new StoreException("Infinity query HTTP " + response.getCode() + ": " + content);
            }
            return parse(content);
        } catch (IOException | ParseException exception) {
            throw new StoreException("Infinity query request failed", exception);
        }
    }

    private JSONObject parse(String content) {
        JSONObject response = JSON.parseObject(content == null ? "{}" : content);
        Integer errorCode = response.getInteger("error_code");
        if (errorCode != null && errorCode != 0) {
            throw new StoreException("Infinity error " + errorCode + ": " + response.getString("error_msg"));
        }
        return response;
    }

    private static Collection<?> documentIds(List<Document> documents) {
        List<Object> ids = new ArrayList<>(documents.size());
        for (Document document : documents) ids.add(document.getId());
        return ids;
    }

    private static List<Double> doubles(float[] vector) {
        List<Double> result = new ArrayList<>(vector.length);
        for (float value : vector) result.add((double) value);
        return result;
    }

    private static float[] parseVector(Object value) {
        if (value == null || "Null".equals(value)) return null;
        JSONArray array = value instanceof JSONArray ? (JSONArray) value : JSON.parseArray(String.valueOf(value));
        float[] result = new float[array.size()];
        for (int i = 0; i < array.size(); i++) result[i] = array.getFloatValue(i);
        return result;
    }

    private static Object nullValue(Object value) {
        return value == null || "Null".equals(value) ? null : value;
    }

    private static String stringValue(Object value) {
        Object normalized = nullValue(value);
        return normalized == null ? null : String.valueOf(normalized);
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : value == null ? null : Double.valueOf(String.valueOf(value));
    }

    private static InfinityMetadataType inferType(Object value) {
        if (value instanceof Boolean) return InfinityMetadataType.BOOLEAN;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) return InfinityMetadataType.INTEGER;
        if (value instanceof Long) return InfinityMetadataType.BIGINT;
        if (value instanceof Float) return InfinityMetadataType.FLOAT;
        if (value instanceof Number) return InfinityMetadataType.DOUBLE;
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>
            || value instanceof Date || value instanceof TemporalAccessor) return InfinityMetadataType.VARCHAR;
        throw new IllegalArgumentException("Unsupported Infinity metadata value: " + value.getClass().getName());
    }

    private static Object normalizeMetadataValue(Object value, InfinityMetadataType type) {
        switch (type) {
            case VARCHAR:
                return value instanceof Date ? ((Date) value).toInstant().toString() : String.valueOf(value);
            case BOOLEAN:
                if (value instanceof Boolean) return value;
                break;
            case INTEGER:
                if (value instanceof Number) return ((Number) value).intValue();
                break;
            case BIGINT:
                if (value instanceof Number) return ((Number) value).longValue();
                break;
            case FLOAT:
                if (value instanceof Number) return ((Number) value).floatValue();
                break;
            case DOUBLE:
                if (value instanceof Number) return ((Number) value).doubleValue();
                break;
            default:
                break;
        }
        throw new IllegalArgumentException("Value " + value + " is incompatible with Infinity type " + type);
    }

    private static InfinityMetadataType metadataType(String infinityType) {
        String type = infinityType == null ? "" : infinityType.toLowerCase();
        if ("varchar".equals(type)) return InfinityMetadataType.VARCHAR;
        if ("integer".equals(type)) return InfinityMetadataType.INTEGER;
        if ("bigint".equals(type)) return InfinityMetadataType.BIGINT;
        if ("float".equals(type)) return InfinityMetadataType.FLOAT;
        if ("double".equals(type)) return InfinityMetadataType.DOUBLE;
        if ("boolean".equals(type)) return InfinityMetadataType.BOOLEAN;
        throw new StoreException("Unsupported Infinity metadata column type: " + infinityType);
    }

    @Override
    public void close() {
        try {
            queryHttpClient.close();
        } catch (IOException exception) {
            throw new StoreException("Failed to close Infinity HTTP client", exception);
        }
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.weaviate;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.Get;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.argument.WhereArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.misc.model.InvertedIndexConfig;
import io.weaviate.client.v1.misc.model.VectorIndexConfig;
import io.weaviate.client.v1.schema.model.DataType;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;

import java.lang.reflect.Array;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Weaviate 官方 Java Client 的文档向量存储。
 *
 * <p>Agents-Flex 的 Collection 映射为 Weaviate Collection（旧 API 中称 Class）。模块将
 * {@link Document} 自带的向量直接写入 Weaviate，并把 {@link SearchWrapper} 条件树转换为服务端
 * {@link WhereFilter}，不会在 Java 内存中做可能破坏 topK 语义的二次过滤。</p>
 *
 * <p>Weaviate 对对象主键要求使用 UUID。本实现根据 Collection 和原始业务 ID 生成稳定 UUID，原始 ID
 * 始终保存在 {@value #ID_PROPERTY} 属性中，因此不同 Collection 使用相同业务 ID 时不会互相覆盖。</p>
 */
public class WeaviateVectorStore extends DocumentStore {

    static final String ID_PROPERTY = "agentsFlexId";
    static final String TITLE_PROPERTY = "title";
    static final String CONTENT_PROPERTY = "content";
    static final String METADATA_PREFIX = "metadata_";

    private final WeaviateVectorStoreConfig config;
    private final WeaviateClient client;
    private final WeaviateConditionBuilder conditionBuilder = new WeaviateConditionBuilder();
    private final Map<String, Object> schemaLocks = new ConcurrentHashMap<>();

    public WeaviateVectorStore(WeaviateVectorStoreConfig config) {
        this(config, createClient(requireConfig(config)));
        Result<Boolean> ready = client.misc().readyChecker().run();
        requireSuccess(ready, "Weaviate connection failed");
        if (!Boolean.TRUE.equals(ready.getResult())) {
            throw new StoreException("Weaviate is not ready: " + config.getServerUrl());
        }
    }

    /** 使用调用方创建的官方 Client，适合统一认证、代理或连接配置。 */
    public WeaviateVectorStore(WeaviateVectorStoreConfig config, WeaviateClient client) {
        this.config = requireConfig(config);
        this.client = Objects.requireNonNull(client, "WeaviateClient cannot be null");
        for (Map.Entry<String, WeaviateMetadataType> entry : config.getMetadataFieldTypes().entrySet()) {
            metadataProperty(entry.getKey());
            Objects.requireNonNull(entry.getValue(), "Weaviate metadata type cannot be null");
        }
    }

    private static WeaviateVectorStoreConfig requireConfig(WeaviateVectorStoreConfig config) {
        Objects.requireNonNull(config, "WeaviateVectorStoreConfig cannot be null");
        if (!config.checkAvailable()) {
            throw new IllegalArgumentException("Weaviate vector store configuration is incomplete");
        }
        return config;
    }

    private static WeaviateClient createClient(WeaviateVectorStoreConfig config) {
        URI uri = config.serverUri();
        Map<String, String> headers = new HashMap<>();
        if (StringUtil.hasText(config.getApiKey())) {
            headers.put("Authorization", "Bearer " + config.getApiKey().trim());
        }
        String host = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        return new WeaviateClient(new Config(uri.getScheme(), host, headers, config.getTimeoutSeconds()));
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
            validateDocuments(documents);
            String collectionName = collectionName(options);
            ensureSchema(collectionName, documents);
            for (Document document : documents) {
                String uuid = objectUuid(collectionName, document.getId());
                Map<String, Object> properties = properties(document);
                Result<Boolean> exists = client.data().checker()
                    .withClassName(collectionName).withID(uuid).run();
                requireSuccess(exists, operation + " object existence check failed");
                if (Boolean.TRUE.equals(exists.getResult())) {
                    requireSuccess(client.data().updater()
                        .withClassName(collectionName)
                        .withID(uuid)
                        .withProperties(properties)
                        .withVector(box(document.getVector()))
                        .run(), operation + " failed");
                } else {
                    requireSuccess(client.data().creator()
                        .withClassName(collectionName)
                        .withID(uuid)
                        .withProperties(properties)
                        .withVector(box(document.getVector()))
                        .run(), operation + " failed");
                }
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
            String collectionName = collectionName(options);
            for (Object id : ids) {
                if (id == null) {
                    throw new IllegalArgumentException("Weaviate document ID cannot be null");
                }
                Result<Boolean> result = client.data().deleter()
                    .withClassName(collectionName)
                    .withID(objectUuid(collectionName, id))
                    .run();
                requireSuccess(result, "Delete failed");
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
            String collectionName = collectionName(options);
            ensureSchema(collectionName, Collections.emptyList());
            Map<String, String> schema = schemaProperties(collectionName);
            Get query = client.graphQL().get()
                .withClassName(collectionName)
                .withFields(queryFields(wrapper, schema))
                .withLimit(wrapper.getMaxResults());

            WhereFilter where = conditionBuilder.build(wrapper.getCondition(), schema);
            if (where != null) {
                query.withWhere(WhereArgument.builder().filter(where).build());
            }
            if (wrapper.isWithVector()) {
                float[] vector = wrapper.getVector();
                if (vector == null || vector.length == 0) {
                    throw new StoreException(
                        "Weaviate vector query requires a vector; use withVector(false) for filter-only queries");
                }
                validateVectorDimension(vector.length);
                NearVectorArgument.NearVectorArgumentBuilder near = NearVectorArgument.builder().vector(box(vector));
                if (wrapper.getMinScore() != null) {
                    near.distance(maxDistance(wrapper.getMinScore()));
                }
                query.withNearVector(near.build());
            }

            Result<GraphQLResponse> result = query.run();
            requireSuccess(result, "Weaviate search failed");
            if (result.getResult() == null || result.getResult().getErrors() != null) {
                throw new StoreException("Weaviate GraphQL search failed: " + result.getResult());
            }
            return parseDocuments(result.getResult(), collectionName, wrapper, schema);
        } catch (StoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StoreException("Weaviate search failed: " + exception.getMessage(), exception);
        }
    }

    private Field[] queryFields(SearchWrapper wrapper, Map<String, String> schema) {
        Set<String> properties = new LinkedHashSet<>();
        Collections.addAll(properties, ID_PROPERTY, TITLE_PROPERTY, CONTENT_PROPERTY);
        if (wrapper.getOutputFields() == null) {
            for (String property : schema.keySet()) {
                if (property.startsWith(METADATA_PREFIX)) {
                    properties.add(property);
                }
            }
        } else {
            for (String outputField : wrapper.getOutputFields()) {
                properties.add(normalizeOutputProperty(outputField));
            }
        }

        List<Field> fields = new ArrayList<>();
        for (String property : properties) {
            fields.add(field(property));
        }
        List<Field> additional = new ArrayList<>();
        Collections.addAll(additional, field("id"), field("distance"));
        if (config.getSimilarity() == WeaviateSimilarity.COSINE) {
            additional.add(field("certainty"));
        }
        if (wrapper.isOutputVector()) {
            additional.add(field("vector"));
        }
        fields.add(Field.builder().name("_additional")
            .fields(additional.toArray(new Field[0])).build());
        return fields.toArray(new Field[0]);
    }

    private Field field(String name) {
        return Field.builder().name(name).build();
    }

    @SuppressWarnings("unchecked")
    private List<Document> parseDocuments(
        GraphQLResponse response,
        String collectionName,
        SearchWrapper wrapper,
        Map<String, String> schema
    ) {
        Object dataObject = response.getData();
        if (!(dataObject instanceof Map<?, ?>)) {
            return Collections.emptyList();
        }
        Object getObject = ((Map<?, ?>) dataObject).get("Get");
        if (!(getObject instanceof Map<?, ?>)) {
            return Collections.emptyList();
        }
        Object rowsObject = ((Map<?, ?>) getObject).get(collectionName);
        if (!(rowsObject instanceof List<?>)) {
            return Collections.emptyList();
        }

        List<Document> documents = new ArrayList<>();
        for (Object rowObject : (List<?>) rowsObject) {
            if (!(rowObject instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) rowObject;
            Document document = new Document();
            document.setId(row.get(ID_PROPERTY));
            document.setTitle(stringValue(row.get(TITLE_PROPERTY)));
            document.setContent(stringValue(row.get(CONTENT_PROPERTY)));
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().startsWith(METADATA_PREFIX)) {
                    String metadataKey = entry.getKey().substring(METADATA_PREFIX.length());
                    Object metadataValue = restoreMetadataValue(entry.getValue(), schema.get(entry.getKey()));
                    if (metadataValue != null) {
                        document.putMetadata(metadataKey, metadataValue);
                    }
                }
            }
            Object additionalObject = row.get("_additional");
            if (additionalObject instanceof Map<?, ?>) {
                Map<String, Object> additional = (Map<String, Object>) additionalObject;
                if (wrapper.isOutputVector() && additional.get("vector") instanceof List<?>) {
                    document.setVectorByNumbers((List<? extends Number>) additional.get("vector"));
                }
                if (wrapper.isWithVector()) {
                    document.setScore(score(additional));
                }
            }
            documents.add(document);
        }
        return documents;
    }

    private Object restoreMetadataValue(Object value, String dataType) {
        if (value == null || dataType == null) {
            return value;
        }
        if (DataType.DATE.equals(dataType) && value instanceof String) {
            return Date.from(Instant.parse((String) value));
        }
        if (DataType.DATE_ARRAY.equals(dataType) && value instanceof List<?>) {
            List<Date> dates = new ArrayList<>();
            for (Object item : (List<?>) value) {
                dates.add(Date.from(Instant.parse(String.valueOf(item))));
            }
            return dates;
        }
        return value;
    }

    private Float score(Map<String, Object> additional) {
        Number distanceNumber = number(additional.get("distance"));
        Number certainty = number(additional.get("certainty"));
        if (config.getSimilarity() == WeaviateSimilarity.COSINE && certainty != null) {
            return certainty.floatValue();
        }
        if (distanceNumber == null) {
            return null;
        }
        double distance = distanceNumber.doubleValue();
        if (config.getSimilarity() == WeaviateSimilarity.DOT) {
            return (float) (1.0d / (1.0d + Math.exp(distance)));
        }
        return (float) (1.0d / (1.0d + Math.max(0.0d, distance)));
    }

    private Float maxDistance(double minScore) {
        switch (config.getSimilarity()) {
            case COSINE:
                return (float) (2.0d * (1.0d - minScore));
            case DOT:
                if (minScore <= 0.0d || minScore >= 1.0d) {
                    throw new IllegalArgumentException("DOT minScore must be greater than 0 and less than 1");
                }
                return (float) Math.log((1.0d / minScore) - 1.0d);
            default:
                if (minScore <= 0.0d) {
                    throw new IllegalArgumentException("Distance-based minScore must be greater than 0");
                }
                return (float) ((1.0d / minScore) - 1.0d);
        }
    }

    private void ensureSchema(String collectionName, List<Document> documents) {
        Object lock = schemaLocks.computeIfAbsent(collectionName, key -> new Object());
        synchronized (lock) {
            Result<Boolean> exists = client.schema().exists().withClassName(collectionName).run();
            requireSuccess(exists, "Weaviate collection existence check failed");
            if (!Boolean.TRUE.equals(exists.getResult())) {
                if (!config.isAutoCreateCollection()) {
                    throw new StoreException("Weaviate collection does not exist: " + collectionName);
                }
                requireSuccess(client.schema().classCreator().withClass(collectionSchema(collectionName)).run(),
                    "Create Weaviate collection failed");
            }

            Map<String, String> existing = schemaProperties(collectionName);
            Map<String, String> required = new LinkedHashMap<>();
            for (Map.Entry<String, WeaviateMetadataType> entry : config.getMetadataFieldTypes().entrySet()) {
                required.put(metadataProperty(entry.getKey()), entry.getValue().getDataType());
            }
            for (Document document : documents) {
                if (document.getMetadataMap() == null) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : document.getMetadataMap().entrySet()) {
                    if (entry.getValue() != null) {
                        String propertyName = metadataProperty(entry.getKey());
                        WeaviateMetadataType declaredType = config.getMetadataFieldTypes().get(entry.getKey());
                        String inferredType = isEmptyArray(entry.getValue()) && declaredType != null
                            ? declaredType.getDataType() : inferDataType(entry.getValue());
                        String previousType = required.put(propertyName, inferredType);
                        if (previousType != null && !previousType.equals(inferredType)) {
                            throw new StoreException("Weaviate metadata field has inconsistent types in one batch: "
                                + propertyName + " uses both " + previousType + " and " + inferredType);
                        }
                    }
                }
            }
            for (Map.Entry<String, String> entry : required.entrySet()) {
                String currentType = existing.get(entry.getKey());
                if (currentType != null && !currentType.equals(entry.getValue())) {
                    throw new StoreException("Weaviate metadata field type conflict: " + entry.getKey()
                        + " is " + currentType + " but value requires " + entry.getValue());
                }
                if (currentType == null) {
                    if (!config.isAutoCreateMetadataProperties()) {
                        throw new StoreException("Weaviate metadata property does not exist: " + entry.getKey());
                    }
                    requireSuccess(client.schema().propertyCreator()
                        .withClassName(collectionName)
                        .withProperty(property(entry.getKey(), entry.getValue()))
                        .run(), "Create Weaviate metadata property failed");
                    existing.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private WeaviateClass collectionSchema(String collectionName) {
        List<Property> properties = new ArrayList<>();
        properties.add(property(ID_PROPERTY, DataType.TEXT));
        properties.add(property(TITLE_PROPERTY, DataType.TEXT));
        properties.add(property(CONTENT_PROPERTY, DataType.TEXT));
        for (Map.Entry<String, WeaviateMetadataType> entry : config.getMetadataFieldTypes().entrySet()) {
            properties.add(property(metadataProperty(entry.getKey()), entry.getValue().getDataType()));
        }
        return WeaviateClass.builder()
            .className(collectionName)
            .description("Agents-Flex document vector collection")
            .vectorizer("none")
            .vectorIndexType("hnsw")
            .vectorIndexConfig(VectorIndexConfig.builder()
                .distance(config.getSimilarity().getDistanceName()).build())
            .invertedIndexConfig(InvertedIndexConfig.builder().indexNullState(true).build())
            .properties(properties)
            .build();
    }

    private Property property(String name, String dataType) {
        Property.PropertyBuilder builder = Property.builder()
            .name(name)
            .dataType(Collections.singletonList(dataType))
            .indexFilterable(true)
            .indexSearchable(DataType.TEXT.equals(dataType));
        if (DataType.TEXT.equals(dataType) || DataType.TEXT_ARRAY.equals(dataType)) {
            builder.tokenization("field");
        }
        if (DataType.INT.equals(dataType) || DataType.NUMBER.equals(dataType)
            || DataType.DATE.equals(dataType)) {
            builder.indexRangeFilters(true);
        }
        return builder.build();
    }

    private Map<String, String> schemaProperties(String collectionName) {
        Result<WeaviateClass> result = client.schema().classGetter().withClassName(collectionName).run();
        requireSuccess(result, "Read Weaviate collection schema failed");
        Map<String, String> properties = new LinkedHashMap<>();
        if (result.getResult() != null && result.getResult().getProperties() != null) {
            for (Property property : result.getResult().getProperties()) {
                if (property.getDataType() != null && !property.getDataType().isEmpty()) {
                    properties.put(property.getName(), property.getDataType().get(0));
                }
            }
        }
        return properties;
    }

    private Map<String, Object> properties(Document document) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ID_PROPERTY, String.valueOf(document.getId()));
        if (document.getTitle() != null) {
            properties.put(TITLE_PROPERTY, document.getTitle());
        }
        if (document.getContent() != null) {
            properties.put(CONTENT_PROPERTY, document.getContent());
        }
        if (document.getMetadataMap() != null) {
            for (Map.Entry<String, Object> entry : document.getMetadataMap().entrySet()) {
                if (entry.getValue() != null) {
                    properties.put(metadataProperty(entry.getKey()), normalizeMetadataValue(entry.getValue()));
                }
            }
        }
        return properties;
    }

    private Object normalizeMetadataValue(Object value) {
        if (value instanceof Date) {
            return Instant.ofEpochMilli(((Date) value).getTime()).toString();
        }
        if (value instanceof Enum<?> || value instanceof Character) {
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> || value.getClass().isArray()) {
            List<Object> normalized = new ArrayList<>();
            int size = value instanceof Collection<?> ? ((Collection<?>) value).size() : Array.getLength(value);
            if (size == 0) {
                return normalized;
            }
            if (value instanceof Collection<?>) {
                for (Object item : (Collection<?>) value) {
                    normalized.add(normalizeArrayItem(item));
                }
            } else {
                for (int index = 0; index < size; index++) {
                    normalized.add(normalizeArrayItem(Array.get(value, index)));
                }
            }
            return normalized;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported Weaviate metadata value: " + value.getClass().getName());
    }

    private Object normalizeArrayItem(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("Weaviate metadata arrays cannot contain null items");
        }
        if (item instanceof Date) {
            return Instant.ofEpochMilli(((Date) item).getTime()).toString();
        }
        if (item instanceof Enum<?> || item instanceof Character) {
            return String.valueOf(item);
        }
        if (item instanceof Number || item instanceof Boolean || item instanceof CharSequence) {
            return item;
        }
        throw new IllegalArgumentException("Unsupported Weaviate metadata array item: "
            + item.getClass().getName());
    }

    private String inferDataType(Object value) {
        Object sample = value;
        boolean array = value instanceof Collection<?> || value.getClass().isArray();
        if (array) {
            int size = value instanceof Collection<?> ? ((Collection<?>) value).size() : Array.getLength(value);
            if (size == 0) {
                throw new IllegalArgumentException(
                    "Empty metadata arrays require an explicit metadataFieldTypes declaration");
            }
            sample = value instanceof Collection<?> ? ((Collection<?>) value).iterator().next() : Array.get(value, 0);
            if (sample == null) {
                throw new IllegalArgumentException("Weaviate metadata arrays cannot contain null items");
            }
        }
        String scalar;
        if (sample instanceof Byte || sample instanceof Short || sample instanceof Integer) {
            scalar = DataType.INT;
        } else if (sample instanceof Number) {
            scalar = DataType.NUMBER;
        } else if (sample instanceof Boolean) {
            scalar = DataType.BOOLEAN;
        } else if (sample instanceof Date) {
            scalar = DataType.DATE;
        } else if (sample instanceof CharSequence || sample instanceof Character || sample instanceof Enum<?>) {
            scalar = DataType.TEXT;
        } else {
            throw new IllegalArgumentException("Unsupported Weaviate metadata value: "
                + sample.getClass().getName());
        }
        return array ? scalar + "[]" : scalar;
    }

    private boolean isEmptyArray(Object value) {
        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).isEmpty();
        }
        return value != null && value.getClass().isArray() && Array.getLength(value) == 0;
    }

    private void validateDocuments(List<Document> documents) {
        Integer dimension = null;
        for (Document document : documents) {
            if (document == null || document.getId() == null) {
                throw new IllegalArgumentException("Weaviate documents and IDs cannot be null");
            }
            float[] vector = document.getVector();
            if (vector == null || vector.length == 0) {
                throw new IllegalArgumentException("Weaviate document vectors cannot be empty");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Weaviate vectors must contain only finite numbers");
                }
            }
            if (dimension == null) {
                dimension = vector.length;
            } else if (dimension != vector.length) {
                throw new IllegalArgumentException("All Weaviate document vectors must have the same dimension");
            }
        }
        validateVectorDimension(dimension);
    }

    private void validateVectorDimension(int dimension) {
        if (config.getVectorDimension() > 0 && dimension != config.getVectorDimension()) {
            throw new IllegalArgumentException("Weaviate vector dimension does not match configured dimension "
                + config.getVectorDimension());
        }
    }

    private Float[] box(float[] vector) {
        Float[] values = new Float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            if (!Float.isFinite(vector[index])) {
                throw new IllegalArgumentException("Weaviate vectors must contain only finite numbers");
            }
            values[index] = vector[index];
        }
        return values;
    }

    private String collectionName(StoreOptions options) {
        String collectionName = options.getCollectionNameOrDefault(config.getDefaultCollectionName());
        validateCollectionName(collectionName);
        return collectionName;
    }

    static void validateCollectionName(String collectionName) {
        if (!StringUtil.hasText(collectionName)
            || !collectionName.matches("[A-Z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                "Weaviate collection name must start with an uppercase letter and contain only letters, digits or _");
        }
    }

    static String metadataProperty(String metadataKey) {
        String key = metadataKey == null ? "" : metadataKey.trim();
        if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                "Weaviate metadata key must contain only letters, digits or _ and cannot be nested: " + metadataKey);
        }
        return METADATA_PREFIX + key;
    }

    private String normalizeOutputProperty(String field) {
        String normalized = field.trim();
        if ("id".equals(normalized) || ID_PROPERTY.equals(normalized)) {
            return ID_PROPERTY;
        }
        if (TITLE_PROPERTY.equals(normalized) || CONTENT_PROPERTY.equals(normalized)) {
            return normalized;
        }
        if (normalized.startsWith("metadataMap.")) {
            normalized = normalized.substring("metadataMap.".length());
        } else if (normalized.startsWith("metadata.")) {
            normalized = normalized.substring("metadata.".length());
        }
        return metadataProperty(normalized);
    }

    private String objectUuid(String collectionName, Object id) {
        String namespacedId = collectionName + '\0' + String.valueOf(id);
        return UUID.nameUUIDFromBytes(namespacedId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void requireSuccess(Result<?> result, String operation) {
        if (result == null || result.hasErrors()) {
            throw new StoreException(operation + ": " + (result == null ? "empty response" : result.getError()));
        }
    }

    private Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    WeaviateClient getClient() {
        return client;
    }
}

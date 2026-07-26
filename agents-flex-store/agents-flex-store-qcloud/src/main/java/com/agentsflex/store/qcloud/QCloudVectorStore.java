/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.qcloud;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.DocField;
import com.tencent.tcvectordb.model.param.dml.DeleteParam;
import com.tencent.tcvectordb.model.param.dml.InsertParam;
import com.tencent.tcvectordb.model.param.dml.QueryParam;
import com.tencent.tcvectordb.model.param.dml.SearchByVectorParam;
import com.tencent.tcvectordb.model.param.dml.UpdateParam;
import com.tencent.tcvectordb.model.param.entity.AffectRes;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于腾讯云官方 Java SDK 的 VectorDB 文档向量存储。
 *
 * <p>Database 来自配置，Collection 可由单次 {@link StoreOptions} 覆盖。
 * 正文和标题使用内部保留的动态标量字段保存。</p>
 *
 * @see <a href="https://cloud.tencent.com/document/product/1709/100555">腾讯云 VectorDB Java SDK</a>
 */
public class QCloudVectorStore extends DocumentStore implements AutoCloseable {

    static final String FIELD_CONTENT = "__agentsflex_content";
    static final String FIELD_TITLE = "__agentsflex_title";

    private static final Set<String> RESERVED_FIELDS;

    static {
        Set<String> fields = new LinkedHashSet<>();
        fields.add(FIELD_CONTENT);
        fields.add(FIELD_TITLE);
        RESERVED_FIELDS = Collections.unmodifiableSet(fields);
    }

    private final QCloudVectorStoreConfig config;
    private final QCloudVectorClient client;

    public QCloudVectorStore(QCloudVectorStoreConfig config) {
        this(config, new TencentVectorSdkClient(config));
    }

    /** 使用调用方创建的官方 SDK Client，关闭 Store 时会同时关闭该 Client。 */
    public QCloudVectorStore(QCloudVectorStoreConfig config, VectorDBClient client) {
        this(config, new TencentVectorSdkClient(client));
    }

    QCloudVectorStore(QCloudVectorStoreConfig config, QCloudVectorClient client) {
        if (config == null) {
            throw new IllegalArgumentException("QCloudVectorStoreConfig must not be null.");
        }
        if (client == null) {
            throw new IllegalArgumentException("QCloudVectorClient must not be null.");
        }
        this.config = config;
        this.client = client;
    }

    @Override
    protected StoreResult doStore(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        try {
            rejectPartitions(options);
            InsertParam param = InsertParam.newBuilder()
                .withDocuments(toSdkDocuments(documents, true, true))
                .build();
            AffectRes response = client.upsert(config.getDatabase(), collectionName(options), param);
            String error = responseError("upsert", response, documents.size(), true);
            return error == null ? StoreResult.successWithIds(documents) : StoreResult.fail(error);
        } catch (Exception exception) {
            return StoreResult.fail("Tencent VectorDB upsert failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    protected StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        try {
            rejectPartitions(options);
            DeleteParam param = DeleteParam.newBuilder().withDocumentIds(toStringIds(ids)).build();
            AffectRes response = client.delete(config.getDatabase(), collectionName(options), param);
            String error = responseError("delete", response, ids.size(), false);
            return error == null ? StoreResult.success() : StoreResult.fail(error);
        } catch (Exception exception) {
            return StoreResult.fail("Tencent VectorDB delete failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    protected StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        try {
            rejectPartitions(options);
            String collection = collectionName(options);
            for (Document document : documents) {
                validateDocument(document, false);
                UpdateParam param = UpdateParam.newBuilder()
                    .addDocumentId(String.valueOf(document.getId()))
                    .build();
                com.tencent.tcvectordb.model.Document update = toSdkDocument(document, false, false);
                AffectRes response = client.update(config.getDatabase(), collection, param, update);
                String error = responseError("update", response, 1, true);
                if (error != null) {
                    return StoreResult.fail(error);
                }
            }
            return StoreResult.successWithIds(documents);
        } catch (Exception exception) {
            return StoreResult.fail("Tencent VectorDB update failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    protected List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        if (wrapper == null) {
            throw new IllegalArgumentException("SearchWrapper must not be null.");
        }
        validateMaxResults(wrapper.getMaxResults());
        rejectPartitions(options);

        try {
            List<com.tencent.tcvectordb.model.Document> sdkDocuments;
            if (wrapper.isWithVector()) {
                if (wrapper.getVector() == null) {
                    throw new IllegalArgumentException(
                        "Tencent VectorDB vector query requires a vector; use withVector(false) for filter-only queries.");
                }
                SearchByVectorParam param = vectorSearchParam(wrapper);
                List<List<com.tencent.tcvectordb.model.Document>> groups = client.search(
                    config.getDatabase(), collectionName(options), param);
                sdkDocuments = groups == null || groups.isEmpty() || groups.get(0) == null
                    ? Collections.emptyList() : groups.get(0);
            } else {
                sdkDocuments = client.query(
                    config.getDatabase(), collectionName(options), filterQueryParam(wrapper));
                if (sdkDocuments == null) {
                    sdkDocuments = Collections.emptyList();
                }
            }

            List<Document> result = new ArrayList<>(sdkDocuments.size());
            for (com.tencent.tcvectordb.model.Document sdkDocument : sdkDocuments) {
                Document document = fromSdkDocument(sdkDocument);
                if (wrapper.getMinScore() == null || document.getScore() == null
                    || document.getScore() >= wrapper.getMinScore()) {
                    result.add(document);
                }
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StoreException("Tencent VectorDB query failed: " + exception.getMessage(), exception);
        }
    }

    SearchByVectorParam vectorSearchParam(SearchWrapper wrapper) {
        SearchByVectorParam.Builder builder = SearchByVectorParam.newBuilder()
            .addVector(wrapper.getVectorAsList())
            .withLimit(wrapper.getMaxResults())
            .withRetrieveVector(wrapper.isOutputVector());
        applySearchOptions(builder, wrapper);
        return builder.build();
    }

    QueryParam filterQueryParam(SearchWrapper wrapper) {
        QueryParam.Builder builder = QueryParam.newBuilder()
            .withLimit(wrapper.getMaxResults())
            .withRetrieveVector(wrapper.isOutputVector());
        String filter = filter(wrapper);
        if (StringUtil.hasText(filter)) {
            builder.withFilter(filter);
        }
        List<String> outputFields = outputFields(wrapper);
        if (outputFields != null) {
            builder.withOutputFields(outputFields);
        }
        return builder.build();
    }

    private void applySearchOptions(SearchByVectorParam.Builder builder, SearchWrapper wrapper) {
        String filter = filter(wrapper);
        if (StringUtil.hasText(filter)) {
            builder.withFilter(filter);
        }
        List<String> outputFields = outputFields(wrapper);
        if (outputFields != null) {
            builder.withOutputFields(outputFields);
        }
    }

    private String filter(SearchWrapper wrapper) {
        return wrapper.toFilterExpression(QCloudExpressionAdaptor.DEFAULT);
    }

    private List<com.tencent.tcvectordb.model.Document> toSdkDocuments(
        List<Document> documents,
        boolean vectorRequired,
        boolean includeId
    ) {
        List<com.tencent.tcvectordb.model.Document> result = new ArrayList<>(documents.size());
        for (Document document : documents) {
            result.add(toSdkDocument(document, vectorRequired, includeId));
        }
        return result;
    }

    private com.tencent.tcvectordb.model.Document toSdkDocument(
        Document document,
        boolean vectorRequired,
        boolean includeId
    ) {
        validateDocument(document, vectorRequired);
        com.tencent.tcvectordb.model.Document.Builder builder =
            com.tencent.tcvectordb.model.Document.newBuilder();
        if (includeId) {
            builder.withId(String.valueOf(document.getId()));
        }
        if (document.getVector() != null) {
            builder.withVector(document.getVectorAsList());
        }
        builder.addDocFields(fields(document));
        return builder.build();
    }

    private void validateDocument(Document document, boolean vectorRequired) {
        if (document == null) {
            throw new IllegalArgumentException("Tencent VectorDB document must not be null.");
        }
        if (document.getId() == null) {
            throw new IllegalArgumentException("Tencent VectorDB document ID must not be null.");
        }
        if (vectorRequired && document.getVector() == null) {
            throw new IllegalArgumentException("Tencent VectorDB document vector must not be null.");
        }
    }

    private List<DocField> fields(Document document) {
        List<DocField> fields = new ArrayList<>();
        if (document.getMetadataMap() != null) {
            for (Map.Entry<String, Object> entry : document.getMetadataMap().entrySet()) {
                if (RESERVED_FIELDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                        "Tencent VectorDB metadata field is reserved: " + entry.getKey());
                }
                fields.add(new DocField(entry.getKey(), normalizeFieldValue(entry.getKey(), entry.getValue())));
            }
        }
        if (document.getContent() != null) {
            fields.add(new DocField(FIELD_CONTENT, document.getContent()));
        }
        if (document.getTitle() != null) {
            fields.add(new DocField(FIELD_TITLE, document.getTitle()));
        }
        return fields;
    }

    private Object normalizeFieldValue(String fieldName, Object value) {
        if (value instanceof String || value instanceof Integer || value instanceof Long
            || value instanceof Float || value instanceof Double || value instanceof JSONObject) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short) {
            return ((Number) value).intValue();
        }
        if (value instanceof BigInteger) {
            try {
                return ((BigInteger) value).longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                    "Tencent VectorDB metadata field '" + fieldName + "' is outside the long range.", exception);
            }
        }
        if (value instanceof BigDecimal) {
            double converted = ((BigDecimal) value).doubleValue();
            if (!Double.isFinite(converted)) {
                throw new IllegalArgumentException(
                    "Tencent VectorDB metadata field '" + fieldName + "' is outside the double range.");
            }
            return converted;
        }
        if (value instanceof Map) {
            return new JSONObject((Map<?, ?>) value);
        }
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException(
                        "Tencent VectorDB array field '" + fieldName + "' only supports strings.");
                }
            }
            return value;
        }
        throw new IllegalArgumentException(
            "Unsupported Tencent VectorDB metadata value for field '" + fieldName + "': "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private Document fromSdkDocument(com.tencent.tcvectordb.model.Document sdkDocument) {
        Document document = new Document();
        document.setId(sdkDocument.getId());
        if (sdkDocument.getVector() instanceof List) {
            List<Number> vector = new ArrayList<>();
            for (Object item : (List<?>) sdkDocument.getVector()) {
                if (!(item instanceof Number)) {
                    throw new StoreException(
                        "Tencent VectorDB returned a non-numeric vector item: " + item);
                }
                vector.add((Number) item);
            }
            document.setVectorByNumbers(vector);
        }
        if (sdkDocument.getScore() != null) {
            document.setScore(sdkDocument.getScore().floatValue());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (sdkDocument.getDocFields() != null) {
            for (DocField field : sdkDocument.getDocFields()) {
                if (FIELD_CONTENT.equals(field.getName())) {
                    document.setContent(field.getValue() == null ? null : String.valueOf(field.getValue()));
                } else if (FIELD_TITLE.equals(field.getName())) {
                    document.setTitle(field.getValue() == null ? null : String.valueOf(field.getValue()));
                } else {
                    metadata.put(field.getName(), field.getValue());
                }
            }
        }
        document.putMetadata(metadata);
        return document;
    }

    private List<String> outputFields(SearchWrapper wrapper) {
        if (wrapper.getOutputFields() == null) {
            return null;
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (String field : wrapper.getOutputFields()) {
            if (StringUtil.hasText(field)
                && !"id".equals(field)
                && !"vector".equals(field)
                && !"score".equals(field)
                && !"title".equals(field)
                && !"content".equals(field)) {
                fields.add(field);
            }
        }
        fields.add(FIELD_CONTENT);
        fields.add(FIELD_TITLE);
        return new ArrayList<>(fields);
    }

    private List<String> toStringIds(Collection<?> ids) {
        List<String> result = new ArrayList<>(ids.size());
        for (Object id : ids) {
            if (id == null) {
                throw new IllegalArgumentException("Tencent VectorDB document ID must not be null.");
            }
            result.add(String.valueOf(id));
        }
        return result;
    }

    private String collectionName(StoreOptions options) {
        StoreOptions resolved = options == null ? StoreOptions.DEFAULT : options;
        String collection = resolved.getCollectionNameOrDefault(config.getDefaultCollectionName());
        if (!StringUtil.hasText(collection)) {
            throw new IllegalArgumentException("Tencent VectorDB collection name must not be blank.");
        }
        return collection;
    }

    private void rejectPartitions(StoreOptions options) {
        StoreOptions resolved = options == null ? StoreOptions.DEFAULT : options;
        if (!resolved.getPartitionNamesOrEmpty().isEmpty()) {
            throw new IllegalArgumentException("Tencent VectorDB does not support StoreOptions partitions.");
        }
    }

    private void validateMaxResults(Integer maxResults) {
        if (maxResults == null || maxResults <= 0) {
            throw new IllegalArgumentException("Tencent VectorDB maxResults must be greater than zero.");
        }
    }

    private String responseError(String operation, AffectRes response, int expected, boolean exactCount) {
        if (response == null) {
            return "Tencent VectorDB " + operation + " failed: empty SDK response";
        }
        if (response.getCode() != 0) {
            return "Tencent VectorDB " + operation + " failed: code=" + response.getCode()
                + ", message=" + response.getMsg() + ", requestId=" + response.getRequestId();
        }
        if (exactCount && response.getAffectedCount() != expected) {
            return "Tencent VectorDB " + operation + " affected " + response.getAffectedCount()
                + " document(s), expected " + expected + ", requestId=" + response.getRequestId();
        }
        return null;
    }

    @Override
    public void close() {
        client.close();
    }
}

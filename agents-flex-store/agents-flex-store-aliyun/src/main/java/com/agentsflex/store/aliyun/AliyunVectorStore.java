/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.aliyun;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import com.aliyun.dashvector.DashVectorClient;
import com.aliyun.dashvector.models.Doc;
import com.aliyun.dashvector.models.DocOpResult;
import com.aliyun.dashvector.models.Vector;
import com.aliyun.dashvector.models.requests.DeleteDocRequest;
import com.aliyun.dashvector.models.requests.QueryDocRequest;
import com.aliyun.dashvector.models.requests.UpdateDocRequest;
import com.aliyun.dashvector.models.requests.UpsertDocRequest;
import com.aliyun.dashvector.models.responses.Response;
import com.aliyun.dashvector.proto.CollectionInfo;

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
 * 基于阿里云官方 Java SDK 的 DashVector 文档向量存储。
 *
 * <p>Collection 可由单次 {@link StoreOptions} 覆盖，Partition 使用第一个
 * {@code partitionName}。标题和正文通过内部保留 Field 完整往返。</p>
 *
 * @see <a href="https://help.aliyun.com/zh/document_detail/2572893.html">DashVector Java SDK</a>
 */
public class AliyunVectorStore extends DocumentStore implements AutoCloseable {

    static final String FIELD_CONTENT = "__agentsflex_content";
    static final String FIELD_TITLE = "__agentsflex_title";

    private static final Set<String> RESERVED_FIELDS;

    static {
        Set<String> fields = new LinkedHashSet<>();
        fields.add(FIELD_CONTENT);
        fields.add(FIELD_TITLE);
        RESERVED_FIELDS = Collections.unmodifiableSet(fields);
    }

    private final AliyunVectorStoreConfig config;
    private final AliyunVectorClient client;

    public AliyunVectorStore(AliyunVectorStoreConfig config) {
        this(config, new DashVectorSdkClient(config));
    }

    /** 使用调用方创建的官方 SDK Client。关闭 Store 时会同时关闭该 Client。 */
    public AliyunVectorStore(AliyunVectorStoreConfig config, DashVectorClient client) {
        this(config, new DashVectorSdkClient(client));
    }

    AliyunVectorStore(AliyunVectorStoreConfig config, AliyunVectorClient client) {
        if (config == null) {
            throw new IllegalArgumentException("AliyunVectorStoreConfig must not be null.");
        }
        if (client == null) {
            throw new IllegalArgumentException("AliyunVectorClient must not be null.");
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
            UpsertDocRequest.UpsertDocRequestBuilder<?, ?> builder =
                UpsertDocRequest.builder().docs(toSdkDocs(documents, true));
            String partition = partition(options);
            if (partition != null) {
                builder.partition(partition);
            }
            Response<List<DocOpResult>> response = client.upsert(collectionName(options), builder.build());
            return writeResult("upsert", response, documents);
        } catch (Exception exception) {
            return StoreResult.fail("DashVector upsert failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    protected StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        try {
            DeleteDocRequest.DeleteDocRequestBuilder builder = DeleteDocRequest.builder().ids(toStringIds(ids));
            String partition = partition(options);
            if (partition != null) {
                builder.partition(partition);
            }
            Response<List<DocOpResult>> response = client.delete(collectionName(options), builder.build());
            return writeResult("delete", response, null);
        } catch (Exception exception) {
            return StoreResult.fail("DashVector delete failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    protected StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        try {
            UpdateDocRequest.UpdateDocRequestBuilder<?, ?> builder =
                UpdateDocRequest.builder().docs(toSdkDocs(documents, false));
            String partition = partition(options);
            if (partition != null) {
                builder.partition(partition);
            }
            Response<List<DocOpResult>> response = client.update(collectionName(options), builder.build());
            return writeResult("update", response, documents);
        } catch (Exception exception) {
            return StoreResult.fail("DashVector update failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    protected List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        if (wrapper == null) {
            throw new IllegalArgumentException("SearchWrapper must not be null.");
        }
        try {
            QueryDocRequest request = toQueryRequest(wrapper, options);
            AliyunVectorClient.QueryResult queryResult = client.query(collectionName(options), request);
            Response<List<Doc>> response = queryResult.getResponse();
            requireSuccess("query", response);

            List<Doc> output = response.getOutput();
            if (output == null || output.isEmpty()) {
                return Collections.emptyList();
            }

            List<Document> documents = new ArrayList<>(output.size());
            for (Doc sdkDoc : output) {
                Document document = fromSdkDoc(sdkDoc, queryResult.getMetric());
                if (wrapper.getMinScore() == null
                    || document.getScore() == null
                    || document.getScore() >= wrapper.getMinScore()) {
                    documents.add(document);
                }
            }
            return documents;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (StoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StoreException("DashVector query failed: " + exception.getMessage(), exception);
        }
    }

    QueryDocRequest toQueryRequest(SearchWrapper wrapper, StoreOptions options) {
        Integer maxResults = wrapper.getMaxResults();
        if (maxResults == null || maxResults <= 0) {
            throw new IllegalArgumentException("DashVector maxResults must be greater than zero.");
        }

        QueryDocRequest.QueryDocRequestBuilder builder = QueryDocRequest.builder()
            .topk(maxResults)
            .includeVector(wrapper.isOutputVector());

        if (wrapper.isWithVector()) {
            if (wrapper.getVector() == null) {
                throw new IllegalArgumentException(
                    "DashVector vector query requires a vector; use withVector(false) for filter-only queries.");
            }
            builder.vector(Vector.builder().value(wrapper.getVectorAsList()).build());
        }

        String filter = wrapper.toFilterExpression(AliyunExpressionAdaptor.DEFAULT);
        if (StringUtil.hasText(filter)) {
            builder.filter(filter);
        }

        List<String> outputFields = outputFields(wrapper);
        if (outputFields != null) {
            builder.outputFields(outputFields);
        }

        String partition = partition(options);
        if (partition != null) {
            builder.partition(partition);
        }
        return builder.build();
    }

    private List<Doc> toSdkDocs(List<Document> documents, boolean vectorRequired) {
        List<Doc> sdkDocs = new ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document == null) {
                throw new IllegalArgumentException("DashVector document must not be null.");
            }
            if (document.getId() == null) {
                throw new IllegalArgumentException("DashVector document ID must not be null.");
            }
            if (vectorRequired && document.getVector() == null) {
                throw new IllegalArgumentException("DashVector document vector must not be null.");
            }

            Doc.DocBuilder builder = Doc.builder()
                .id(String.valueOf(document.getId()))
                .fields(fields(document));
            if (document.getVector() != null) {
                builder.vector(Vector.builder().value(document.getVectorAsList()).build());
            }
            sdkDocs.add(builder.build());
        }
        return sdkDocs;
    }

    private Map<String, Object> fields(Document document) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (document.getMetadataMap() != null) {
            for (Map.Entry<String, Object> entry : document.getMetadataMap().entrySet()) {
                if (RESERVED_FIELDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                        "DashVector metadata field is reserved: " + entry.getKey());
                }
                fields.put(entry.getKey(), normalizeFieldValue(entry.getKey(), entry.getValue()));
            }
        }
        if (document.getContent() != null) {
            fields.put(FIELD_CONTENT, document.getContent());
        }
        if (document.getTitle() != null) {
            fields.put(FIELD_TITLE, document.getTitle());
        }
        return fields;
    }

    private Object normalizeFieldValue(String fieldName, Object value) {
        if (value instanceof String || value instanceof Integer || value instanceof Long
            || value instanceof Float || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short) {
            return ((Number) value).intValue();
        }
        if (value instanceof Double || value instanceof BigDecimal) {
            double doubleValue = ((Number) value).doubleValue();
            float floatValue = (float) doubleValue;
            if (!Double.isFinite(doubleValue) || !Float.isFinite(floatValue)) {
                throw new IllegalArgumentException(
                    "DashVector metadata field '" + fieldName + "' is outside the float range.");
            }
            return floatValue;
        }
        if (value instanceof BigInteger) {
            try {
                return ((BigInteger) value).longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                    "DashVector metadata field '" + fieldName + "' is outside the long range.", exception);
            }
        }
        throw new IllegalArgumentException(
            "Unsupported DashVector metadata value for field '" + fieldName
                + "': " + (value == null ? "null" : value.getClass().getName()));
    }

    private Document fromSdkDoc(Doc sdkDoc, CollectionInfo.Metric metric) {
        Document document = new Document();
        document.setId(sdkDoc.getId());
        if (sdkDoc.getVector() != null && sdkDoc.getVector().getValue() != null) {
            document.setVectorByNumbers(sdkDoc.getVector().getValue());
        }
        document.setScore(normalizeScore(sdkDoc.getScore(), metric));

        Map<String, Object> fields = sdkDoc.getFields();
        if (fields != null && !fields.isEmpty()) {
            Map<String, Object> metadata = new LinkedHashMap<>(fields);
            Object content = metadata.remove(FIELD_CONTENT);
            Object title = metadata.remove(FIELD_TITLE);
            document.setContent(content == null ? null : String.valueOf(content));
            document.setTitle(title == null ? null : String.valueOf(title));
            document.putMetadata(metadata);
        }
        return document;
    }

    private Float normalizeScore(float value, CollectionInfo.Metric metric) {
        if (metric == CollectionInfo.Metric.cosine) {
            return clamp(1.0f - value / 2.0f);
        }
        if (metric == CollectionInfo.Metric.euclidean) {
            return 1.0f / (1.0f + Math.max(0.0f, value));
        }
        // DashVector 的 dotproduct score 本身越大越相似，且没有固定范围。
        return value;
    }

    private Float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
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
                throw new IllegalArgumentException("DashVector document ID must not be null.");
            }
            result.add(String.valueOf(id));
        }
        return result;
    }

    private String collectionName(StoreOptions options) {
        StoreOptions resolved = options == null ? StoreOptions.DEFAULT : options;
        String collectionName = resolved.getCollectionNameOrDefault(config.getDefaultCollectionName());
        if (!StringUtil.hasText(collectionName)) {
            throw new IllegalArgumentException("DashVector collection name must not be blank.");
        }
        return collectionName;
    }

    private String partition(StoreOptions options) {
        StoreOptions resolved = options == null ? StoreOptions.DEFAULT : options;
        List<String> partitions = resolved.getPartitionNamesOrEmpty();
        if (partitions.size() > 1) {
            throw new IllegalArgumentException("DashVector supports one partition per request.");
        }
        return partitions.isEmpty() ? null : partitions.get(0);
    }

    private StoreResult writeResult(
        String operation,
        Response<List<DocOpResult>> response,
        List<Document> documents
    ) {
        if (!Boolean.TRUE.equals(response == null ? null : response.isSuccess())) {
            return StoreResult.fail(responseMessage(operation, response));
        }
        List<DocOpResult> output = response.getOutput();
        if (output != null) {
            for (DocOpResult item : output) {
                if (item != null && item.getCode() != 0) {
                    return StoreResult.fail(
                        "DashVector " + operation + " partially failed: id=" + item.getId()
                            + ", code=" + item.getCode() + ", message=" + item.getMessage()
                            + ", requestId=" + response.getRequestId());
                }
            }
        }
        return documents == null ? StoreResult.success() : StoreResult.successWithIds(documents);
    }

    private void requireSuccess(String operation, Response<?> response) {
        if (!Boolean.TRUE.equals(response == null ? null : response.isSuccess())) {
            throw new StoreException(responseMessage(operation, response));
        }
    }

    private String responseMessage(String operation, Response<?> response) {
        if (response == null) {
            return "DashVector " + operation + " failed: empty SDK response";
        }
        return "DashVector " + operation + " failed: code=" + response.getCode()
            + ", message=" + response.getMessage() + ", requestId=" + response.getRequestId();
    }

    @Override
    public void close() {
        client.close();
    }
}

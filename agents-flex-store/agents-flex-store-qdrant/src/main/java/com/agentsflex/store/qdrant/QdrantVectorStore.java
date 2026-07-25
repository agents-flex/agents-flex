/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.store.qdrant;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.ValueFactory.list;
import static io.qdrant.client.ValueFactory.nullValue;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

public class QdrantVectorStore extends DocumentStore {

    static final String ID_PAYLOAD_KEY = "__agentsflex_id";
    static final String TITLE_PAYLOAD_KEY = "__agentsflex_title";
    static final String CONTENT_PAYLOAD_KEY = "__agentsflex_content";

    private final QdrantVectorStoreConfig config;
    private final QdrantClient client;
    private final String defaultCollectionName;
    private final QdrantConditionBuilder conditionBuilder = new QdrantConditionBuilder();

    public QdrantVectorStore(QdrantVectorStoreConfig config) throws IOException {
        this.config = Objects.requireNonNull(config, "QdrantVectorStoreConfig cannot be null");
        this.defaultCollectionName = config.getDefaultCollectionName();
        String uri = config.getUri();
        if (!StringUtil.hasText(uri)) {
            throw new IllegalArgumentException("Qdrant URI cannot be blank");
        }
        int port = 6334;
        QdrantGrpcClient.Builder builder;
        if (StringUtil.hasText(config.getCaPath())) {
            ManagedChannel channel = Grpc.newChannelBuilder(
                uri,
                TlsChannelCredentials.newBuilder().trustManager(new File(config.getCaPath())).build()
            ).build();
            builder = QdrantGrpcClient.newBuilder(channel, true);
        } else {
            int separator = uri.lastIndexOf(':');
            if (separator > 0 && separator == uri.indexOf(':')) {
                String portText = uri.substring(separator + 1).trim();
                uri = uri.substring(0, separator).trim();
                try {
                    port = Integer.parseInt(portText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid Qdrant port: " + portText, e);
                }
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Qdrant port must be between 1 and 65535");
                }
            }
            builder = QdrantGrpcClient.newBuilder(uri, port, false);
        }
        if (StringUtil.hasText(config.getApiKey())) {
            builder.withApiKey(config.getApiKey());
        }
        this.client = new QdrantClient(builder.build());
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
            ensureCollection(collectionName, dimension);
            List<Points.PointStruct> points = documents.stream()
                .map(this::point)
                .collect(Collectors.toList());
            client.upsertAsync(collectionName, points).get();
            return StoreResult.successWithIds(documents);
        } catch (Exception e) {
            Exception cause = unwrap(e);
            return StoreResult.fail(operation + " failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        try {
            String collectionName = collectionName(options);
            List<Points.PointId> pointIds = ids.stream().map(this::pointId).collect(Collectors.toList());
            client.deleteAsync(collectionName, pointIds).get();
            return StoreResult.success();
        } catch (Exception e) {
            Exception cause = unwrap(e);
            return StoreResult.fail("Delete failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        Objects.requireNonNull(wrapper, "SearchWrapper cannot be null");
        if (wrapper.getVector() == null || wrapper.getVector().length == 0) {
            throw new StoreException("Qdrant search requires a non-empty vector");
        }
        validateMinScore(wrapper.getMinScore());
        try {
            Points.QueryPoints.Builder query = Points.QueryPoints.newBuilder()
                .setCollectionName(collectionName(options))
                .setLimit(wrapper.getMaxResults() != null && wrapper.getMaxResults() > 0
                    ? wrapper.getMaxResults() : 10)
                .setWithVectors(WithVectorsSelectorFactory.enable(wrapper.isOutputVector()))
                .setWithPayload(payloadSelector(wrapper))
                .setQuery(nearest(wrapper.getVector()));
            if (wrapper.getCondition() != null) {
                Points.Filter filter = conditionBuilder.build(wrapper.getCondition());
                if (filter != null) {
                    query.setFilter(filter);
                }
            }
            if (wrapper.getMinScore() != null) {
                query.setScoreThreshold(wrapper.getMinScore().floatValue());
            }
            List<Document> documents = new ArrayList<>();
            for (Points.ScoredPoint point : client.queryAsync(query.build()).get()) {
                documents.add(document(point, wrapper));
            }
            return documents;
        } catch (Exception e) {
            Exception cause = unwrap(e);
            throw cause instanceof StoreException ? (StoreException) cause
                : new StoreException("Qdrant search failed: " + cause.getMessage(), cause);
        }
    }

    private Points.PointStruct point(Document document) {
        Map<String, JsonWithInt.Value> payload = new HashMap<>();
        if (document.getMetadataMap() != null) {
            for (Map.Entry<String, Object> entry : document.getMetadataMap().entrySet()) {
                if (isReserved(entry.getKey())) {
                    throw new IllegalArgumentException("Reserved Qdrant metadata key: " + entry.getKey());
                }
                payload.put(entry.getKey(), payloadValue(entry.getValue()));
            }
        }
        payload.put(ID_PAYLOAD_KEY, idPayloadValue(document.getId()));
        payload.put(CONTENT_PAYLOAD_KEY, payloadValue(document.getContent()));
        payload.put(TITLE_PAYLOAD_KEY, payloadValue(document.getTitle()));
        return Points.PointStruct.newBuilder()
            .setId(pointId(document.getId()))
            .setVectors(vectors(document.getVector()))
            .putAllPayload(payload)
            .build();
    }

    private Document document(Points.ScoredPoint point, SearchWrapper wrapper) {
        Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
        Document document = new Document();
        JsonWithInt.Value originalId = payload.get(ID_PAYLOAD_KEY);
        document.setId(originalId == null ? pointIdValue(point.getId()) : convertQdrantValue(originalId));
        document.setContent(stringValue(payload.get(CONTENT_PAYLOAD_KEY)));
        document.setTitle(stringValue(payload.get(TITLE_PAYLOAD_KEY)));
        for (Map.Entry<String, JsonWithInt.Value> entry : payload.entrySet()) {
            if (!isReserved(entry.getKey()) && outputField(wrapper, entry.getKey())) {
                document.putMetadata(entry.getKey(), convertQdrantValue(entry.getValue()));
            }
        }
        document.setScore(point.getScore());
        if (wrapper.isOutputVector() && point.getVectors().hasVector()) {
            Points.VectorOutput vector = point.getVectors().getVector();
            List<Float> values = vector.hasDense()
                ? vector.getDense().getDataList() : vector.getDataList();
            if (!values.isEmpty()) {
                document.setVectorByNumbers(values);
            }
        }
        return document;
    }

    private Points.WithPayloadSelector payloadSelector(SearchWrapper wrapper) {
        if (wrapper.getOutputFields() == null) {
            return WithPayloadSelectorFactory.enable(true);
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        fields.add(ID_PAYLOAD_KEY);
        fields.add(TITLE_PAYLOAD_KEY);
        fields.add(CONTENT_PAYLOAD_KEY);
        for (String field : wrapper.getOutputFields()) {
            fields.add(normalizeOutputField(field));
        }
        return WithPayloadSelectorFactory.include(new ArrayList<>(fields));
    }

    private boolean outputField(SearchWrapper wrapper, String field) {
        if (wrapper.getOutputFields() == null) {
            return true;
        }
        for (String outputField : wrapper.getOutputFields()) {
            if (field.equals(normalizeOutputField(outputField))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeOutputField(String field) {
        if (field.startsWith("metadataMap.")) {
            return field.substring("metadataMap.".length());
        }
        if (field.startsWith("metadata.")) {
            return field.substring("metadata.".length());
        }
        if ("id".equals(field)) {
            return ID_PAYLOAD_KEY;
        }
        if ("title".equals(field)) {
            return TITLE_PAYLOAD_KEY;
        }
        if ("content".equals(field)) {
            return CONTENT_PAYLOAD_KEY;
        }
        return field;
    }

    private int validateDocuments(List<Document> documents) {
        Integer dimension = null;
        for (Document document : documents) {
            if (document == null || document.getId() == null) {
                throw new IllegalArgumentException("Qdrant documents and document IDs cannot be null");
            }
            if (document.getVector() == null || document.getVector().length == 0) {
                throw new IllegalArgumentException("Qdrant document vectors cannot be empty");
            }
            if (document.getMetadataMap() != null) {
                for (String key : document.getMetadataMap().keySet()) {
                    if (isReserved(key)) {
                        throw new IllegalArgumentException("Reserved Qdrant metadata key: " + key);
                    }
                }
            }
            if (dimension == null) {
                dimension = document.getVector().length;
            } else if (dimension != document.getVector().length) {
                throw new IllegalArgumentException("All Qdrant vectors must have the same dimension");
            }
        }
        return dimension;
    }

    private void ensureCollection(String collectionName, int dimension) throws Exception {
        if (client.collectionExistsAsync(collectionName).get()) {
            return;
        }
        if (!config.isAutoCreateCollection()) {
            throw new StoreException("Qdrant collection does not exist: " + collectionName);
        }
        try {
            client.createCollectionAsync(collectionName, Collections.VectorParams.newBuilder()
                .setDistance(Collections.Distance.Cosine)
                .setSize(dimension)
                .build()).get();
        } catch (ExecutionException createFailure) {
            if (!client.collectionExistsAsync(collectionName).get()) {
                throw createFailure;
            }
        }
    }

    private Points.PointId pointId(Object id) {
        if (id instanceof Byte || id instanceof Short || id instanceof Integer || id instanceof Long) {
            long number = ((Number) id).longValue();
            if (number < 0) {
                throw new IllegalArgumentException("Qdrant numeric point IDs cannot be negative");
            }
            return Points.PointId.newBuilder().setNum(number).build();
        }
        String stringId = String.valueOf(id);
        UUID uuid;
        try {
            uuid = UUID.fromString(stringId);
        } catch (IllegalArgumentException ignored) {
            uuid = UUID.nameUUIDFromBytes(("agents-flex:" + stringId).getBytes(StandardCharsets.UTF_8));
        }
        return Points.PointId.newBuilder().setUuid(uuid.toString()).build();
    }

    private Object pointIdValue(Points.PointId id) {
        return id.hasUuid() ? id.getUuid() : id.getNum();
    }

    private JsonWithInt.Value payloadValue(Object source) {
        if (source == null) {
            return nullValue();
        }
        if (source instanceof String || source instanceof Character) {
            return value(String.valueOf(source));
        }
        if (source instanceof Byte || source instanceof Short || source instanceof Integer
            || source instanceof Long) {
            return value(((Number) source).longValue());
        }
        if (source instanceof Number) {
            return value(((Number) source).doubleValue());
        }
        if (source instanceof Boolean) {
            return value((Boolean) source);
        }
        if (source instanceof Collection) {
            List<JsonWithInt.Value> values = new ArrayList<>();
            for (Object item : (Collection<?>) source) {
                values.add(payloadValue(item));
            }
            return list(values);
        }
        if (source.getClass().isArray()) {
            List<JsonWithInt.Value> values = new ArrayList<>();
            for (int i = 0; i < Array.getLength(source); i++) {
                values.add(payloadValue(Array.get(source, i)));
            }
            return list(values);
        }
        if (source instanceof Map) {
            Map<String, JsonWithInt.Value> values = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) source).entrySet()) {
                values.put(String.valueOf(entry.getKey()), payloadValue(entry.getValue()));
            }
            return value(values);
        }
        throw new IllegalArgumentException("Unsupported Qdrant payload value: " + source.getClass().getName());
    }

    private JsonWithInt.Value idPayloadValue(Object id) {
        if (id instanceof Byte || id instanceof Short || id instanceof Integer || id instanceof Long) {
            return value(((Number) id).longValue());
        }
        return value(String.valueOf(id));
    }

    private Object convertQdrantValue(JsonWithInt.Value value) {
        switch (value.getKindCase()) {
            case STRING_VALUE: return value.getStringValue();
            case INTEGER_VALUE: return value.getIntegerValue();
            case DOUBLE_VALUE: return value.getDoubleValue();
            case BOOL_VALUE: return value.getBoolValue();
            case NULL_VALUE: return null;
            case LIST_VALUE:
                return value.getListValue().getValuesList().stream()
                    .map(this::convertQdrantValue).collect(Collectors.toList());
            case STRUCT_VALUE:
                Map<String, Object> map = new HashMap<>();
                value.getStructValue().getFieldsMap()
                    .forEach((key, item) -> map.put(key, convertQdrantValue(item)));
                return map;
            default: return null;
        }
    }

    private String stringValue(JsonWithInt.Value value) {
        Object converted = value == null ? null : convertQdrantValue(value);
        return converted == null ? null : String.valueOf(converted);
    }

    private boolean isReserved(String key) {
        return ID_PAYLOAD_KEY.equals(key) || TITLE_PAYLOAD_KEY.equals(key)
            || CONTENT_PAYLOAD_KEY.equals(key);
    }

    private String collectionName(StoreOptions options) {
        String collectionName = options == null
            ? defaultCollectionName : options.getCollectionNameOrDefault(defaultCollectionName);
        if (!StringUtil.hasText(collectionName)) {
            throw new IllegalArgumentException("Qdrant collection name cannot be blank");
        }
        return collectionName;
    }

    private void validateMinScore(Double minScore) {
        if (minScore != null && (minScore < 0 || minScore > 1)) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
    }

    private Exception unwrap(Exception exception) {
        if (exception instanceof ExecutionException && exception.getCause() instanceof Exception) {
            return (Exception) exception.getCause();
        }
        return exception;
    }

    public QdrantClient getClient() {
        return client;
    }

    public void close() {
        client.close();
    }
}

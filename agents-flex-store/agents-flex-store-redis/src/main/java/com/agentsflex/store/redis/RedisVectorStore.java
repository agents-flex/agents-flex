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
package com.agentsflex.store.redis;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import kotlin.collections.ArrayDeque;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.FieldName;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RedisVectorStore extends DocumentStore {

    protected final RedisVectorStoreConfig config;
    protected final JedisPooled jedis;
    protected final Set<String> redisIndexesCache = ConcurrentHashMap.newKeySet();
    protected final ConcurrentMap<String, Object> indexLocks = new ConcurrentHashMap<>();
    protected final ConcurrentMap<String, ConcurrentMap<String, MetadataFieldType>> metadataFieldsCache = new ConcurrentHashMap<>();
    protected static final Logger logger = LoggerFactory.getLogger(RedisVectorStore.class);

    protected static final Set<String> RESERVED_FIELDS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("text", "vector", "score"))
    );


    public RedisVectorStore(RedisVectorStoreConfig config) {
        this.config = config;
        this.jedis = new JedisPooled(
            URI.create(config.getUri())
        );
    }


    protected void createSchemaIfNecessary(String indexName) {
        if (redisIndexesCache.contains(indexName)) {
            return;
        }

        synchronized (getIndexLock(indexName)) {
            if (redisIndexesCache.contains(indexName)) {
                return;
            }

            Map<String, Object> indexInfo = getIndexInfo(indexName);
            if (indexInfo == null) {
                FTCreateParams ftCreateParams = FTCreateParams.createParams()
                    .on(IndexDataType.JSON)
                    .addPrefix(getPrefix(indexName));

                try {
                    jedis.ftCreate(indexName, ftCreateParams, schemaFields());
                    indexInfo = getIndexInfo(indexName);
                } catch (JedisDataException e) {
                    // Another process may have created the index after our FT.INFO call.
                    if (!isIndexAlreadyExists(e)) {
                        throw e;
                    }
                    indexInfo = getRequiredIndexInfo(indexName);
                }
            }

            validateIndexSchema(indexName, indexInfo);
            cacheMetadataFields(indexName, indexInfo);
            redisIndexesCache.add(indexName);
        }
    }


    protected Iterable<SchemaField> schemaFields() {
        Map<String, Object> vectorAttrs = new HashMap<>();
        //支持  COSINE: 余弦距离 , IP: 内积距离, L2: 欧几里得距离
        vectorAttrs.put("DISTANCE_METRIC", "COSINE");
        vectorAttrs.put("TYPE", "FLOAT32");
        vectorAttrs.put("DIM", this.getEmbeddingModel().dimensions());

        List<SchemaField> fields = new ArrayList<>();
        fields.add(TextField.of(jsonPath("text")).as("text").weight(1.0));

        fields.add(VectorField.builder()
            .fieldName(jsonPath("vector"))
            .algorithm(VectorField.VectorAlgorithm.HNSW)
            .attributes(vectorAttrs)
            .as("vector")
            .build());

        return fields;
    }

    protected String jsonPath(String field) {
        return "$." + field;
    }


    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        String indexName = createIndexName(options);

        if (StringUtil.noText(indexName)) {
            throw new IllegalStateException("IndexName is null or blank. please config the \"defaultCollectionName\" or store with designative collectionName.");
        }

        Map<String, MetadataFieldType> metadataFields = collectMetadataFields(indexName, documents);
        createSchemaIfNecessary(indexName);
        createMetadataSchemaIfNecessary(indexName, metadataFields);

        try (Pipeline pipeline = jedis.pipelined();) {
            for (Document document : documents) {
                java.util.Map<String, Object> fields = new HashMap<>();

                //put all metadata
                Map<String, Object> metadataMap = document.getMetadataMap();
                if (metadataMap != null) {
                    fields.putAll(metadataMap);
                }

                // Core fields must never be overwritten by metadata.
                fields.put("text", document.getContent());
                fields.put("vector", document.getVector());

                String key = getPrefix(indexName) + document.getId();
                pipeline.jsonSetWithEscape(key, Path2.of("$"), fields);
            }

            List<Object> objects = pipeline.syncAndReturnAll();
            for (Object object : objects) {
                if (!object.equals("OK")) {
                    logger.error("Could not store document: {}", object);
                    return StoreResult.fail();
                }
            }
        }

        return StoreResult.successWithIds(documents);
    }


    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        String indexName = createIndexName(options);
        if (StringUtil.noText(indexName)) {
            throw new IllegalStateException("IndexName is null or blank. please config the \"defaultCollectionName\" or delete with designative collectionName.");
        }
        try (Pipeline pipeline = this.jedis.pipelined()) {
            for (Object id : ids) {
                String key = getPrefix(indexName) + id;
                pipeline.jsonDel(key);
            }

            List<Object> objects = pipeline.syncAndReturnAll();
            for (Object object : objects) {
                if (!object.equals(1L)) {
                    logger.error("Could not delete document: {}", object);
                    return StoreResult.fail();
                }
            }
        }

        return StoreResult.success();
    }


    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        return doStore(documents, options);
    }


    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        String indexName = createIndexName(options);

        if (StringUtil.noText(indexName)) {
            throw new IllegalStateException("IndexName is null or blank. please config the \"defaultCollectionName\" or store with designative collectionName.");
        }

        createSchemaIfNecessary(indexName);

        validateMinScore(wrapper.getMinScore());

        // 创建查询向量
        byte[] vectorBytes = new byte[wrapper.getVector().length * 4];
        FloatBuffer floatBuffer = ByteBuffer.wrap(vectorBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        for (Float v : wrapper.getVector()) {
            floatBuffer.put(v);
        }


        List<FieldName> returnFields = new ArrayList<>();
        returnFields.add(FieldName.of("text"));
        if (wrapper.isOutputVector()) {
            returnFields.add(FieldName.of("vector"));
        }
        returnFields.add(FieldName.of("score"));

        if (wrapper.getOutputFields() != null) {
            Set<String> outputFields = new LinkedHashSet<>(wrapper.getOutputFields());
            outputFields.removeAll(RESERVED_FIELDS);
            for (String outputField : outputFields) {
                returnFields.add(FieldName.of(metadataJsonPath(outputField)).as(outputField));
            }
        }

        String filterExpression = createFilterExpression(indexName, wrapper.getCondition());
        String queryExpression = (StringUtil.hasText(filterExpression) ? "(" + filterExpression + ")" : "*")
            + "=>[KNN " + wrapper.getMaxResults() + " @vector $BLOB AS score]";

        // 使用 KNN 算法进行向量相似度搜索
        Query query = new Query(queryExpression)
            .addParam("BLOB", vectorBytes)
            .returnFields(returnFields.toArray(new FieldName[0]))
            .setSortBy("score", true)
            .limit(0, wrapper.getMaxResults())
            .dialect(2);

        int keyPrefixLen = this.getPrefix(indexName).length();

        // 执行搜索
        SearchResult searchResult = jedis.ftSearch(indexName, query);
        List<redis.clients.jedis.search.Document> searchDocuments = searchResult.getDocuments();
        List<Document> documents = new ArrayDeque<>(searchDocuments.size());
        for (redis.clients.jedis.search.Document document : searchDocuments) {
            String id = document.getId().substring(keyPrefixLen);
            Document doc = new Document();
            doc.setId(id);
            doc.setContent(document.getString("text"));
            if (wrapper.isOutputVector()) {
                Object vector = document.get("vector");
                if (vector != null) {
                    float[] floats = JSON.parseObject(vector.toString(), float[].class);
                    doc.setVector(floats);
                }
            }

            if (wrapper.getOutputFields() != null) {
                for (String field : wrapper.getOutputFields()) {
                    doc.putMetadata(field, document.getString(field));
                }
            }

            float score = similarityScore(document);
            if (wrapper.getMinScore() != null && score < wrapper.getMinScore()) {
                continue;
            }
            doc.setScore(score);
            documents.add(doc);
        }
        return documents;
    }

    protected float similarityScore(redis.clients.jedis.search.Document doc) {
        return (2 - Float.parseFloat(doc.getString("score"))) / 2;
    }


    protected String createIndexName(StoreOptions options) {
        return options.getCollectionNameOrDefault(config.getDefaultCollectionName());
    }

    @NotNull
    protected String getPrefix(String indexName) {
        return this.config.getStorePrefix() + indexName + ":";
    }

    protected Object getIndexLock(String indexName) {
        return indexLocks.computeIfAbsent(indexName, key -> new Object());
    }

    protected Map<String, Object> getIndexInfo(String indexName) {
        try {
            return jedis.ftInfo(indexName);
        } catch (JedisDataException e) {
            if (isUnknownIndex(e)) {
                return null;
            }
            throw e;
        }
    }

    protected Map<String, Object> getRequiredIndexInfo(String indexName) {
        Map<String, Object> indexInfo = getIndexInfo(indexName);
        if (indexInfo == null) {
            throw new IllegalStateException("Redis index does not exist after creation: " + indexName);
        }
        return indexInfo;
    }

    protected boolean isUnknownIndex(JedisDataException e) {
        String message = e.getMessage();
        return message != null && (message.toLowerCase(Locale.ROOT).contains("unknown index")
            || message.toLowerCase(Locale.ROOT).contains("no such index"));
    }

    protected boolean isIndexAlreadyExists(JedisDataException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("index already exists");
    }

    protected void validateIndexSchema(String indexName, Map<String, Object> indexInfo) {
        Map<String, Object> definition = asMap(indexInfo.get("index_definition"));
        List<?> prefixes = asList(definition.get("prefixes"));
        String expectedPrefix = getPrefix(indexName);
        if (prefixes.size() != 1 || !expectedPrefix.equals(String.valueOf(prefixes.get(0)))) {
            throw new IllegalStateException("Redis index '" + indexName + "' has incompatible prefixes. expected=["
                + expectedPrefix + "], actual=" + prefixes);
        }

        Map<String, Object> textField = findAttribute(indexInfo, "text");
        if (!"TEXT".equalsIgnoreCase(stringValue(textField.get("type")))) {
            throw new IllegalStateException("Redis index '" + indexName + "' has an incompatible text field.");
        }

        Map<String, Object> vectorField = findAttribute(indexInfo, "vector");
        int expectedDimensions = getEmbeddingModel().dimensions();
        if (!"VECTOR".equalsIgnoreCase(stringValue(vectorField.get("type")))
            || !"HNSW".equalsIgnoreCase(stringValue(vectorField.get("algorithm")))
            || !"FLOAT32".equalsIgnoreCase(stringValue(vectorField.get("data_type")))
            || !"COSINE".equalsIgnoreCase(stringValue(vectorField.get("distance_metric")))
            || expectedDimensions != intValue(vectorField.get("dim"))) {
            throw new IllegalStateException("Redis index '" + indexName + "' has an incompatible vector field.");
        }
    }

    protected Map<String, MetadataFieldType> collectMetadataFields(String indexName, List<Document> documents) {
        Map<String, MetadataFieldType> requestedFields = new LinkedHashMap<>();
        for (Document document : documents) {
            Map<String, Object> metadataMap = document.getMetadataMap();
            if (metadataMap == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : metadataMap.entrySet()) {
                validateMetadataFieldName(entry.getKey());
                if (entry.getValue() == null) {
                    continue;
                }
                MetadataFieldType fieldType = metadataFieldType(entry.getValue());
                MetadataFieldType previous = requestedFields.put(entry.getKey(), fieldType);
                if (previous != null && previous != fieldType) {
                    throw incompatibleMetadataType(indexName, entry.getKey(), previous, fieldType);
                }
            }
        }
        return requestedFields;
    }

    protected void createMetadataSchemaIfNecessary(String indexName, Map<String, MetadataFieldType> requestedFields) {
        for (Map.Entry<String, MetadataFieldType> entry : requestedFields.entrySet()) {
            createMetadataFieldIfNecessary(indexName, entry.getKey(), entry.getValue());
        }
    }

    protected void createMetadataFieldIfNecessary(String indexName, String fieldName, MetadataFieldType fieldType) {
        ConcurrentMap<String, MetadataFieldType> fields = metadataFieldsCache.computeIfAbsent(
            indexName, key -> new ConcurrentHashMap<>()
        );
        MetadataFieldType existingType = fields.get(fieldName);
        if (existingType != null) {
            if (existingType != fieldType) {
                throw incompatibleMetadataType(indexName, fieldName, existingType, fieldType);
            }
            return;
        }

        synchronized (getIndexLock(indexName)) {
            existingType = fields.get(fieldName);
            if (existingType != null) {
                if (existingType != fieldType) {
                    throw incompatibleMetadataType(indexName, fieldName, existingType, fieldType);
                }
                return;
            }

            SchemaField schemaField = fieldType == MetadataFieldType.NUMERIC
                ? NumericField.of(metadataJsonPath(fieldName)).as(metadataFieldAlias(fieldName))
                : TagField.of(metadataJsonPath(fieldName)).as(metadataFieldAlias(fieldName));
            try {
                jedis.ftAlter(indexName, schemaField);
            } catch (JedisDataException e) {
                Map<String, Object> indexInfo = getRequiredIndexInfo(indexName);
                cacheMetadataFields(indexName, indexInfo);
                existingType = fields.get(fieldName);
                if (existingType == fieldType) {
                    return;
                }
                throw e;
            }
            fields.put(fieldName, fieldType);
        }
    }

    protected String createFilterExpression(String indexName, Condition condition) {
        if (condition == null || !condition.isEffective()) {
            return null;
        }
        return condition.toExpression(new RedisExpressionAdaptor(indexName, this));
    }

    protected void cacheMetadataFields(String indexName, Map<String, Object> indexInfo) {
        ConcurrentMap<String, MetadataFieldType> fields = metadataFieldsCache.computeIfAbsent(
            indexName, key -> new ConcurrentHashMap<>()
        );
        for (Object attributeObject : asList(indexInfo.get("attributes"))) {
            Map<String, Object> attribute = asMap(attributeObject);
            String alias = stringValue(attribute.get("attribute"));
            if (alias == null || !alias.startsWith("metadata_")) {
                continue;
            }
            String fieldName = decodeMetadataFieldAlias(alias);
            MetadataFieldType type = MetadataFieldType.fromRedisType(stringValue(attribute.get("type")));
            if (fieldName != null && type != null) {
                fields.put(fieldName, type);
            }
        }
    }

    protected Map<String, Object> findAttribute(Map<String, Object> indexInfo, String alias) {
        for (Object attributeObject : asList(indexInfo.get("attributes"))) {
            Map<String, Object> attribute = asMap(attributeObject);
            if (alias.equals(stringValue(attribute.get("attribute")))) {
                return attribute;
            }
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        if (!(value instanceof List)) {
            return Collections.emptyMap();
        }
        List<?> values = (List<?>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.size(); i += 2) {
            result.put(String.valueOf(values.get(i)), values.get(i + 1));
        }
        return result;
    }

    protected List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    protected String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    protected int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    protected void validateMetadataFieldName(String fieldName) {
        if (StringUtil.noText(fieldName)) {
            throw new IllegalArgumentException("Metadata field name must not be blank.");
        }
        if (RESERVED_FIELDS.contains(fieldName)) {
            throw new IllegalArgumentException("Metadata field '" + fieldName + "' is reserved by RedisVectorStore.");
        }
    }

    protected MetadataFieldType metadataFieldType(Object value) {
        return value instanceof Number ? MetadataFieldType.NUMERIC : MetadataFieldType.TAG;
    }

    protected IllegalStateException incompatibleMetadataType(String indexName, String fieldName,
                                                               MetadataFieldType existing, MetadataFieldType requested) {
        return new IllegalStateException("Redis index '" + indexName + "' metadata field '" + fieldName
            + "' has type " + existing + " but " + requested + " was requested.");
    }

    protected String metadataJsonPath(String fieldName) {
        return "$['" + fieldName.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    protected String metadataFieldAlias(String fieldName) {
        return "metadata_" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(fieldName.getBytes(StandardCharsets.UTF_8));
    }

    protected String decodeMetadataFieldAlias(String alias) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(alias.substring("metadata_".length()));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    protected void validateMinScore(Double minScore) {
        if (minScore != null && (minScore < 0.0d || minScore > 1.0d)) {
            throw new IllegalArgumentException("minScore must be between 0 and 1.");
        }
    }

    protected enum MetadataFieldType {
        TAG,
        NUMERIC;

        static MetadataFieldType fromRedisType(String type) {
            if ("TAG".equalsIgnoreCase(type)) {
                return TAG;
            }
            if ("NUMERIC".equalsIgnoreCase(type)) {
                return NUMERIC;
            }
            return null;
        }
    }


}

/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import kotlin.collections.ArrayDeque;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.*;

public class RedisVectorStore extends DocumentStore {

    protected final RedisVectorStoreConfig config;
    protected final JedisPooled jedis;
    protected final Set<String> redisIndexesCache = new HashSet<>();
    protected static final Logger logger = LoggerFactory.getLogger(RedisVectorStore.class);


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

        // 检查 indexName 是否存在
        Set<String> existIndexes = this.jedis.ftList();
        if (existIndexes != null && existIndexes.contains(indexName)) {
            redisIndexesCache.add(indexName);
            return;
        }

        FTCreateParams ftCreateParams = FTCreateParams.createParams()
            .on(IndexDataType.JSON)
            .addPrefix(getPrefix(indexName));

        jedis.ftCreate(indexName, ftCreateParams, schemaFields());
        redisIndexesCache.add(indexName);
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
    public StoreResult storeInternal(List<Document> documents, StoreOptions options) {
        String indexName = createIndexName(options);

        if (StringUtil.noText(indexName)) {
            throw new IllegalStateException("IndexName is null or blank. please config the \"defaultCollectionName\" or store with designative collectionName.");
        }

        createSchemaIfNecessary(indexName);

        try (Pipeline pipeline = jedis.pipelined();) {
            for (Document document : documents) {
                java.util.Map<String, Object> fields = new HashMap<>();
                fields.put("text", document.getContent());
                fields.put("vector", document.getVector());

                //put all metadata
                Map<String, Object> metadataMap = document.getMetadataMap();
                if (metadataMap != null) {
                    fields.putAll(metadataMap);
                }

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
    public StoreResult deleteInternal(Collection<?> ids, StoreOptions options) {
        String indexName = createIndexName(options);
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
    public StoreResult updateInternal(List<Document> documents, StoreOptions options) {
        return storeInternal(documents, options);
    }


    @Override
    public List<Document> searchInternal(SearchWrapper wrapper, StoreOptions options) {
        String indexName = createIndexName(options);

        if (StringUtil.noText(indexName)) {
            throw new IllegalStateException("IndexName is null or blank. please config the \"defaultCollectionName\" or store with designative collectionName.");
        }

        createSchemaIfNecessary(indexName);

        // 创建查询向量
        byte[] vectorBytes = new byte[wrapper.getVector().length * 4];
        FloatBuffer floatBuffer = ByteBuffer.wrap(vectorBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        for (Double v : wrapper.getVector()) {
            floatBuffer.put(v.floatValue());
        }


        List<String> returnFields = new ArrayList<>();
        returnFields.add("text");
        returnFields.add("vector");
        returnFields.add("score");

        if (wrapper.getOutputFields() != null) {
            returnFields.addAll(wrapper.getOutputFields());
        }

        // 使用 KNN 算法进行向量相似度搜索
        Query query = new Query("*=>[KNN " + wrapper.getMaxResults() + " @vector $BLOB AS score]")
            .addParam("BLOB", vectorBytes)
            .returnFields(returnFields.toArray(new String[0]))
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
            Object vector = document.get("vector");
            if (vector != null) {
                double[] doubles = JSON.parseObject(vector.toString(), double[].class);
                doc.setVector(doubles);
            }

            if (wrapper.getOutputFields() != null) {
                for (String field : wrapper.getOutputFields()) {
                    doc.addMetadata(field, document.getString(field));
                }
            }

            doc.addMetadata("score", 1 - similarityScore(document));

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


}

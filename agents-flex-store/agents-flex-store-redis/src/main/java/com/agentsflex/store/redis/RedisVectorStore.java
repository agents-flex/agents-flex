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
import redis.clients.jedis.*;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.*;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.MessageFormat;
import java.util.*;

public class RedisVectorStore extends DocumentStore {

    private final RedisVectorStoreConfig config;
    private final JedisPooled jedis;
    private final Set<String> redisIndexesCache = new HashSet<>();

    public RedisVectorStore(RedisVectorStoreConfig config) {
        this.config = config;
        this.jedis = new JedisPooled(
            URI.create(config.getUri())
        );
    }


    private void createSchemaIfNecessary(String indexName) {
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
            .addPrefix(this.config.getStorePrefix());

        jedis.ftCreate(indexName, ftCreateParams, schemaFields());
        redisIndexesCache.add(indexName);
    }


    private Iterable<SchemaField> schemaFields() {
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

    private String jsonPath(String field) {
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

                String key = config.getStorePrefix() + document.getId();
                pipeline.jsonSetWithEscape(key, Path2.of("$"), fields);
            }

            List<Object> objects = pipeline.syncAndReturnAll();
            for (Object object : objects) {
                if (!object.equals("OK")) {
                    String message = MessageFormat.format("Could not store document: {0}", object);
                    throw new RuntimeException(message);
                }
            }
        }

        return StoreResult.successWithIds(documents);
    }


    @Override
    public StoreResult deleteInternal(Collection<Object> ids, StoreOptions options) {
        try (Pipeline pipeline = this.jedis.pipelined()) {
            for (Object id : ids) {
                String key = config.getStorePrefix() + id;
                pipeline.jsonDel(key);
            }

            List<Object> objects = pipeline.syncAndReturnAll();
            for (Object object : objects) {
                if (!object.equals(1L)) {
                    String message = MessageFormat.format("Could not delete document: {0}", object);
                    throw new RuntimeException(message);
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

        // 使用 KNN 算法进行向量相似度搜索
        Query query = new Query("*=>[KNN " + wrapper.getMaxResults() + " @vector $BLOB AS score]")
            .addParam("BLOB", vectorBytes)
            .returnFields("text", "vector", "score")
            .setSortBy("score", true)
            .dialect(2);

        // 执行搜索
        SearchResult searchResult = jedis.ftSearch(indexName, query);
        List<redis.clients.jedis.search.Document> searchDocuments = searchResult.getDocuments();
        List<Document> documents = new ArrayDeque<>(searchDocuments.size());
        for (redis.clients.jedis.search.Document document : searchDocuments) {
            String id = document.getId().substring(this.config.getStorePrefix().length());
            Document doc = new Document();
            doc.setId(id);
            doc.setContent(document.getString("text"));
            Object vector = document.get("vector");
            if (vector != null) {
                double[] doubles = JSON.parseObject(vector.toString(), double[].class);
                doc.setVector(doubles);
            }
            doc.addMetadata("score", 1 - similarityScore(document));
            documents.add(doc);
        }
        return documents;
    }

    private float similarityScore(redis.clients.jedis.search.Document doc) {
        return (2 - Float.parseFloat(doc.getString("score"))) / 2;
    }


    private String createIndexName(StoreOptions options) {
        return options.getCollectionNameOrDefault(config.getDefaultCollectionName());
    }


}

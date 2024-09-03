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
import kotlin.collections.ArrayDeque;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RedisVectorStore extends DocumentStore {

    private final RedisVectorStoreConfig config;
    private final UnifiedJedis unifiedjedis;

    public RedisVectorStore(RedisVectorStoreConfig config) {
        this.config = config;
        this.unifiedjedis = new UnifiedJedis(
            URI.create(config.getUri()),
            DefaultJedisClientConfig.builder()
                .user(config.getUser())
                .password(config.getPassword())
                .database(config.getDatabase())
                .build()
        );
    }


    private void createSchemaIfNecessary(String indexName) {

        // 检查 indexName 是否存在
        if (this.unifiedjedis.ftList().contains(indexName)) {
            return;
        }

        String pre = "doc:" + indexName + ":";

        IndexDefinition definition = new IndexDefinition().setPrefixes(new String[]{pre});
        Schema schema = new Schema();

        Map<String, Object> vectorAttrs = new HashMap<>();
        vectorAttrs.put("TYPE", "FLOAT32");
        vectorAttrs.put("DIM", this.getEmbeddingModel().dimensions());

        //支持  COSINE: 余弦距离 , IP: 内积距离, L2: 欧几里得距离
        vectorAttrs.put("DISTANCE_METRIC", "COSINE");

        schema.addHNSWVectorField("vector", vectorAttrs);


        schema.addTextField("text", 1);

        unifiedjedis.ftCreate(indexName, IndexOptions.defaultOptions().setDefinition(definition), schema);
    }


    @Override
    public StoreResult storeInternal(List<Document> documents, StoreOptions options) {

        String indexName = "doc:" + options.getCollectionNameOrDefault(config.getDefaultCollectionName());
        createSchemaIfNecessary(indexName);

        for (Document document : documents) {
            String key = indexName + ":" + document.getId();
            java.util.Map<String, Object> fields = new HashMap<>();
            fields.put("text", document.getContent());
            fields.put("vector", document.getVector());

            unifiedjedis.hsetObject(key, fields);
        }
        return StoreResult.successWithIds(documents);
    }


    @Override
    public StoreResult deleteInternal(Collection<Object> ids, StoreOptions options) {
        for (Object id : ids) {
            String key = "doc:" + options.getCollectionNameOrDefault(config.getDefaultCollectionName()) + ":" + id;
            unifiedjedis.del(key);
        }
        return StoreResult.success();
    }


    @Override
    public StoreResult updateInternal(List<Document> documents, StoreOptions options) {
        return storeInternal(documents, options);
    }


    @Override
    public List<Document> searchInternal(SearchWrapper wrapper, StoreOptions options) {
        // 创建查询向量
        byte[] vectorBytes = new byte[wrapper.getVector().length * 4];
        ByteBuffer byteBuffer = ByteBuffer.wrap(vectorBytes);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        for (Double v : wrapper.getVector()) {
            byteBuffer.putFloat(v.floatValue());
        }

        // 使用 KNN 算法进行向量相似度搜索
        Query query = new Query("*=>[KNN " + wrapper.getMaxResults() + " @vector $vector AS score]")
            .addParam("vector", vectorBytes)
            .limit(0, wrapper.getMaxResults())
            .dialect(2);

        // 执行搜索
        SearchResult searchResult = unifiedjedis.ftSearch("doc:" + options.getCollectionNameOrDefault(config.getDefaultCollectionName()), query);
        List<redis.clients.jedis.search.Document> documents = searchResult.getDocuments();
        List<Document> results = new ArrayDeque<>(documents.size());
        for (redis.clients.jedis.search.Document document : documents) {
            Document doc = new Document();
            doc.setId(document.getId());
            doc.setContent(document.getString("text"));
            doc.setVector((double[]) document.get("vector"));
            results.add(doc);
        }

        return results;
    }
}

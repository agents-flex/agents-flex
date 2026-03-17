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
package com.agentsflex.store.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.ScriptScoreQuery;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * es 向量存储：<a href="https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/introduction.html">elasticsearch-java</a>
 *
 * @author songyinyin
 * @since 2024/8/12 下午4:17
 */
public class ElasticSearchVectorStore extends DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(ElasticSearchVectorStore.class);

    private final ElasticsearchClient client;

    private final ElasticSearchVectorStoreConfig config;

    public ElasticSearchVectorStore(ElasticSearchVectorStoreConfig config) {
        this.config = config;
        RestClientBuilder restClientBuilder = RestClient.builder(HttpHost.create(config.getServerUrl()));

        try {
            SSLContext sslContext = SSLContextBuilder.create().loadTrustMaterial(null, (chains, authType) -> true).build();

            if (StringUtil.hasText(config.getUsername())) {
                CredentialsProvider provider = new BasicCredentialsProvider();
                provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(config.getUsername(), config.getPassword()));
                restClientBuilder.setHttpClientConfigCallback(httpClientBuilder -> {
                    httpClientBuilder.setSSLContext(sslContext);
                    httpClientBuilder.setDefaultCredentialsProvider(provider);
                    return httpClientBuilder;
                });
            }

            if (StringUtil.hasText(config.getApiKey())) {
                restClientBuilder.setDefaultHeaders(new Header[]{
                    new BasicHeader("Authorization", "Apikey " + config.getApiKey())
                });
            }

            ElasticsearchTransport transport = new RestClientTransport(restClientBuilder.build(), new JacksonJsonpMapper());

            this.client = new ElasticsearchClient(transport);
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new StoreException("Elasticsearch init error", e);
        }
        try {
            client.ping();
        } catch (IOException e) {
            log.error("[I/O Elasticsearch Exception]", e);
            throw new StoreException(e.getMessage());
        }
    }

    public ElasticSearchVectorStore(ElasticSearchVectorStoreConfig config, ElasticsearchClient client) {
        this.config = config;
        this.client = client;
    }

    private static void throwIfError(BulkResponse bulkResponse) {
        if (bulkResponse.errors()) {
            for (BulkResponseItem item : bulkResponse.items()) {
                if (item.error() == null) {
                    continue;
                }
                ErrorCause errorCause = item.error();
                throw new StoreException("type: " + errorCause.type() + "," + "reason: " + errorCause.reason());
            }
        }
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        String indexName;
        if (StringUtil.hasText(options.getCollectionName())) {
            indexName = options.getCollectionName();
        } else {
            indexName = options.getIndexNameOrDefault(config.getDefaultIndexName());
        }
        createIndexIfNotExist(indexName);
        return saveOrUpdate(documents, indexName);
    }

    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        String indexName = options.getIndexNameOrDefault(config.getDefaultIndexName());
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (Object id : ids) {
            bulkBuilder.operations(op -> op.delete(d -> d.index(indexName).id(id.toString())));
        }
        bulk(bulkBuilder.build());
        return StoreResult.success();
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        String indexName = options.getIndexNameOrDefault(config.getDefaultIndexName());
        return saveOrUpdate(documents, indexName);
    }

    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        // 最小匹配分数，无值则默认0
        Double minScore = wrapper.getMinScore();
        // 获取索引名，无指定则使用配置的默认索引
        String indexName = options.getIndexNameOrDefault(config.getDefaultIndexName());

        // 公式：(cosineSimilarity + 1.0) / 2  将相似度映射到 0~1 区间
        ScriptScoreQuery scriptScoreQuery = ScriptScoreQuery.of(fn -> fn
            .minScore(minScore == null ? 0 : minScore.floatValue())
            .query(Query.of(q -> q.matchAll(m -> m)))
            .script(s -> s
                .source("(cosineSimilarity(params.query_vector, 'vector') + 1.0) / 2")
                .params("query_vector", JsonData.of(wrapper.getVector()))
            )
        );

        try {
            SearchResponse<JsonData> response = client.search(
                SearchRequest.of(s -> s.index(indexName)
                    .query(n -> n.scriptScore(scriptScoreQuery))
                    .size(wrapper.getMaxResults())),
                JsonData.class
            );

            return response.hits().hits().stream()
                .filter(hit -> hit.source() != null) // 过滤_source为空的无效结果
                .map(hit -> parseFromJsonData(hit.source(), hit.score()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("[es/search] Elasticsearch I/O exception occurred", e);
            throw new StoreException(e.getMessage());
        }
    }

    private StoreResult saveOrUpdate(List<Document> documents, String indexName) {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (Document document : documents) {
            bulkBuilder.operations(op -> op.index(
                idx -> idx.index(indexName).id(document.getId().toString()).document(document))
            );
        }
        bulk(bulkBuilder.build());
        return StoreResult.successWithIds(documents);
    }

    private void bulk(BulkRequest bulkRequest) {
        try {
            BulkResponse bulkResponse = client.bulk(bulkRequest);
            throwIfError(bulkResponse);
        } catch (IOException e) {
            log.error("[I/O Elasticsearch Exception]", e);
            throw new StoreException(e.getMessage());
        }
    }

    private void createIndexIfNotExist(String indexName) {
        try {
            BooleanResponse response = client.indices().exists(c -> c.index(indexName));
            if (!response.value()) {
                log.info("[ElasticSearch] Index {} not exists, creating...", indexName);
                client.indices().create(c -> c.index(indexName)
                    .mappings(getDefaultMappings(this.getEmbeddingModel().dimensions())));
            }
        } catch (IOException e) {
            log.error("[I/O ElasticSearch Exception]", e);
            throw new StoreException(e.getMessage());
        }
    }

    private TypeMapping getDefaultMappings(int dimension) {
        Map<String, Property> properties = new HashMap<>(4);
        properties.put("content", Property.of(p -> p.text(TextProperty.of(t -> t))));
        properties.put("vector", Property.of(p -> p.denseVector(DenseVectorProperty.of(d -> d.dims(dimension)))));
        return TypeMapping.of(c -> c.properties(properties));
    }

    private Document parseFromJsonData(JsonData source, Double score) {
        Document document = new Document();
        Map<String, Object> dataMap = source.to(Map.class);

        document.setId(dataMap.get("id"));
        document.setTitle((String) dataMap.get("title"));
        document.setContent((String) dataMap.get("content"));
        document.setScore(score == null ? null : score.floatValue());

        Object vectorObj = dataMap.get("vector");
        if (vectorObj instanceof List<?>) {
            List<?> vectorList = (List<?>) vectorObj;
            float[] vector = new float[vectorList.size()];
            for (int i = 0; i < vectorList.size(); i++) {
                Object val = vectorList.get(i);
                if (val instanceof Number) {
                    vector[i] = ((Number) val).floatValue();
                }
            }
            document.setVector(vector);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> metadataMap = (Map<String, Object>) dataMap.get("metadataMap");
        if (metadataMap != null && !metadataMap.isEmpty()) {
            document.setMetadataMap(metadataMap);
        } else {
            Map<String, Object> otherMetadata = new HashMap<>();
            for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                String key = entry.getKey();
                if (!"id".equals(key) && !"title".equals(key)
                    && !"content".equals(key) && !"vector".equals(key)) {
                    otherMetadata.put(key, entry.getValue());
                }
            }
            if (!otherMetadata.isEmpty()) {
                document.setMetadataMap(otherMetadata);
            }
        }

        return document;
    }
}

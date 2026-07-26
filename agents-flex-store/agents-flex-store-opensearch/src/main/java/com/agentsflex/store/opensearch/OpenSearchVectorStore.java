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
package com.agentsflex.store.opensearch;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.store.exception.StoreException;
import com.agentsflex.core.util.StringUtil;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.InlineScript;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TextProperty;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.ScriptScoreQuery;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

/**
 * 基于 OpenSearch Java Client 的文档向量存储。
 *
 * <p>集合映射为索引，负责文档批量写入、按 ID 删除、更新和 kNN 检索。
 * 元数据条件由 {@link OpenSearchExpressionAdaptor} 转换为服务端过滤表达式；实例持有底层连接，
 * 使用完毕后应调用 {@link #close()}。</p>
 *
 * @author songyinyin
 * @since 2024/8/10 下午8:31
 */
public class OpenSearchVectorStore extends DocumentStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchVectorStore.class);

    private final OpenSearchClient client;

    private final OpenSearchVectorStoreConfig config;

    public OpenSearchVectorStore(OpenSearchVectorStoreConfig config) {
        this.config = config;
        HttpHost openSearchHost;
        try {
            openSearchHost = HttpHost.create(config.getServerUrl());
        } catch (URISyntaxException se) {
            log.error("[OpenSearch Exception]", se);
            throw new StoreException(se.getMessage());
        }

        OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
            .builder(openSearchHost)
            .setMapper(new JacksonJsonpMapper())
            .setHttpClientConfigCallback(httpClientBuilder -> {

                if (StringUtil.hasText(config.getApiKey())) {
                    httpClientBuilder.setDefaultHeaders(singletonList(
                        new BasicHeader("Authorization", "ApiKey " + config.getApiKey())
                    ));
                }

                if (StringUtil.hasText(config.getUsername()) && StringUtil.hasText(config.getPassword())) {
                    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(new AuthScope(openSearchHost),
                        new UsernamePasswordCredentials(config.getUsername(), config.getPassword().toCharArray()));
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }

                return httpClientBuilder;
            })
            .build();

        this.client = new OpenSearchClient(transport);
        try {
            client.ping();
        } catch (IOException | RuntimeException e) {
            log.error("[I/O OpenSearch Exception]", e);
            try {
                transport.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            throw new StoreException(e.getMessage(), e);
        }
    }

    public OpenSearchVectorStore(OpenSearchVectorStoreConfig config, OpenSearchClient client) {
        this.config = config;
        this.client = client;
    }

    private void createIndexIfNotExist(String indexName, int dimension) {
        try {
            BooleanResponse response = client.indices().exists(c -> c.index(indexName));
            if (!response.value()) {
                log.info("[OpenSearch] Index {} not exists, creating...", indexName);
                client.indices().create(c -> c.index(indexName)
                    .settings(s -> s.knn(true))
                    .mappings(getDefaultMappings(dimension)));
            }
        } catch (IOException | RuntimeException e) {
            log.error("[I/O OpenSearch Exception]", e);
            throw asStoreException(e);
        }
    }

    private TypeMapping getDefaultMappings(int dimension) {
        Map<String, Property> properties = new HashMap<>(4);
        properties.put("content", Property.of(p -> p.text(TextProperty.of(t -> t))));
        properties.put("vector", Property.of(p -> p.knnVector(
            k -> k.dimension(dimension)
        )));
        return TypeMapping.of(c -> c.properties(properties));
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        String indexName = resolveIndexName(options);
        createIndexIfNotExist(indexName, resolveDimension(documents));
        return saveOrUpdate(documents, indexName);
    }

    private void bulk(BulkRequest bulkRequest) {
        try {
            BulkResponse bulkResponse = client.bulk(bulkRequest);
            throwIfError(bulkResponse);
        } catch (IOException | RuntimeException e) {
            log.error("[I/O OpenSearch Exception]", e);
            throw asStoreException(e);
        }
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
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        if (ids == null || ids.isEmpty()) {
            return StoreResult.success();
        }
        String indexName = resolveIndexName(options);
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder().refresh(Refresh.WaitFor);
        for (Object id : ids) {
            bulkBuilder.operations(op -> op.delete(d -> d.index(indexName).id(id.toString())));
        }
        bulk(bulkBuilder.build());
        return StoreResult.success();
    }

    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        String indexName = resolveIndexName(options);
        createIndexIfNotExist(indexName, resolveDimension(documents));
        return saveOrUpdate(documents, indexName);
    }

    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        Double minScore = wrapper.getMinScore();
        String indexName = resolveIndexName(options);

        Query filterQuery = Query.of(q -> q.matchAll(m -> m));
        String filterExpression = wrapper.toFilterExpression(OpenSearchExpressionAdaptor.DEFAULT);
        if (StringUtil.hasText(filterExpression)) {
            filterQuery = Query.of(q -> q.queryString(query -> query.query(filterExpression)));
        }
        final Query effectiveFilterQuery = filterQuery;

        Query searchQuery;
        if (wrapper.isWithVector()) {
            float[] vector = wrapper.getVector();
            if (vector == null || vector.length == 0) {
                throw new IllegalArgumentException(
                    "OpenSearch vector query requires a vector; use withVector(false) for filter-only queries.");
            }
            ScriptScoreQuery scriptScoreQuery = ScriptScoreQuery.of(q -> q
                .minScore(minScore == null ? 0 : minScore.floatValue())
                .query(effectiveFilterQuery)
                .script(s -> s.inline(InlineScript.of(i -> i
                    .source("knn_score")
                    .lang("knn")
                    .params("field", JsonData.of("vector"))
                    .params("query_value", JsonData.of(vector))
                    .params("space_type", JsonData.of("cosinesimil"))
                ))));
            searchQuery = Query.of(q -> q.scriptScore(scriptScoreQuery));
        } else {
            searchQuery = effectiveFilterQuery;
        }

        try {
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(indexName)
                .query(searchQuery)
                .size(wrapper.getMaxResults());
            applySourceFilter(searchBuilder, wrapper);
            SearchResponse<JsonData> response = client.search(searchBuilder.build(), JsonData.class);
            return response.hits().hits().stream()
                .filter(s -> s.source() != null)
                .map(s -> parseFromJsonData(s.source(), s.score(), wrapper.isOutputVector()))
                .collect(toList());
        } catch (IOException | RuntimeException e) {
            log.error("[I/O OpenSearch Exception]", e);
            throw asStoreException(e);
        }
    }

    private static StoreException asStoreException(Exception exception) {
        if (exception instanceof StoreException) {
            return (StoreException) exception;
        }
        return new StoreException(exception.getMessage(), exception);
    }

    private StoreResult saveOrUpdate(List<Document> documents, String indexName) {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder().refresh(Refresh.WaitFor);
        for (Document document : documents) {
            bulkBuilder.operations(op -> op.index(
                idx -> idx.index(indexName).id(document.getId().toString()).document(toSource(document)))
            );
        }
        bulk(bulkBuilder.build());
        return StoreResult.successWithIds(documents);
    }

    private String resolveIndexName(StoreOptions options) {
        if (StringUtil.hasText(options.getCollectionName())) {
            return options.getCollectionName();
        }
        return options.getIndexNameOrDefault(config.getDefaultIndexName());
    }

    private int resolveDimension(List<Document> documents) {
        for (Document document : documents) {
            if (document != null && document.getVector() != null && document.getVector().length > 0) {
                return document.getVector().length;
            }
        }
        if (getEmbeddingModel() != null && getEmbeddingModel().dimensions() > 0) {
            return getEmbeddingModel().dimensions();
        }
        throw new StoreException("Cannot create OpenSearch index without a document vector or embedding model dimension");
    }

    private void applySourceFilter(SearchRequest.Builder searchBuilder, SearchWrapper wrapper) {
        if (wrapper.getOutputFields() == null) {
            if (!wrapper.isOutputVector()) {
                searchBuilder.source(source -> source.filter(filter -> filter.excludes("vector")));
            }
            return;
        }

        LinkedHashSet<String> fields = new LinkedHashSet<>();
        Collections.addAll(fields, "id", "title", "content");
        fields.addAll(wrapper.getOutputFields());
        if (wrapper.isOutputVector()) {
            fields.add("vector");
        } else {
            fields.remove("vector");
        }
        searchBuilder.source(source -> source.filter(filter -> filter.includes(new ArrayList<>(fields))));
    }

    private Map<String, Object> toSource(Document document) {
        Map<String, Object> source = new HashMap<>();
        source.put("id", document.getId());
        source.put("title", document.getTitle());
        source.put("content", document.getContent());
        source.put("vector", document.getVector());
        source.put("metadataMap", document.getMetadataMap());
        return source;
    }

    private Document parseFromJsonData(JsonData source, Double score, boolean outputVector) {
        StoredDocument stored = source.to(StoredDocument.class);
        Document document = new Document();
        document.setId(stored.getId());
        document.setTitle(stored.getTitle());
        document.setContent(stored.getContent());
        document.setScore(score == null ? null : score.floatValue());

        if (outputVector && stored.getVector() != null) {
            document.setVectorByNumbers(stored.getVector());
        }
        if (stored.getMetadataMap() != null) {
            document.setMetadataMap(stored.getMetadataMap());
        }
        return document;
    }

    /** 仅描述持久化字段，避免把 Document 的派生 getter 当作 OpenSearch 字段。 */
    private static class StoredDocument {
        private Object id;
        private String title;
        private String content;
        private List<Number> vector;
        private Map<String, Object> metadataMap;

        public Object getId() {
            return id;
        }

        public void setId(Object id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public List<Number> getVector() {
            return vector;
        }

        public void setVector(List<Number> vector) {
            this.vector = vector;
        }

        public Map<String, Object> getMetadataMap() {
            return metadataMap;
        }

        public void setMetadataMap(Map<String, Object> metadataMap) {
            this.metadataMap = metadataMap;
        }
    }

    @Override
    public void close() {
        try {
            client._transport().close();
        } catch (IOException e) {
            throw new StoreException("Failed to close OpenSearch client", e);
        }
    }
}

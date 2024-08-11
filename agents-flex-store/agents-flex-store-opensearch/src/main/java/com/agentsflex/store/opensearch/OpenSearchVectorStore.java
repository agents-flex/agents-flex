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
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.InlineScript;
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

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

/**
 * OpenSearch 向量存储
 *
 * @author songyinyin
 * @since 2024/8/10 下午8:31
 */
public class OpenSearchVectorStore extends DocumentStore {

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

        try {
            SSLContext sslContext = SSLContextBuilder.create().loadTrustMaterial(null, (chains, authType) -> true).build();
            TlsStrategy tlsStrategy = ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();

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

                    httpClientBuilder.setConnectionManager(PoolingAsyncClientConnectionManagerBuilder
                        .create().setTlsStrategy(tlsStrategy).build());

                    return httpClientBuilder;
                })
                .build();

            this.client = new OpenSearchClient(transport);
            try {
                client.ping();
            } catch (IOException e) {
                log.error("[I/O OpenSearch Exception]", e);
                throw new StoreException(e.getMessage());
            }
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new StoreException("OpenSearchClient init error", e);
        }
    }

    private void createIndexIfNotExist(String indexName) {
        try {
            BooleanResponse response = client.indices().exists(c -> c.index(indexName));
            if (!response.value()) {
                log.info("[OpenSearch] Index {} not exists, creating...", indexName);
                client.indices().create(c -> c.index(indexName)
                    .settings(s -> s.knn(true))
                    .mappings(getDefaultMappings(this.getEmbeddingModel().dimensions())));
            }
        } catch (IOException e) {
            log.error("[I/O OpenSearch Exception]", e);
            throw new StoreException(e.getMessage());
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
    public StoreResult storeInternal(List<Document> documents, StoreOptions options) {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        String indexName = options.getIndexNameOrDefault(config.getDefaultIndexName());
        createIndexIfNotExist(indexName);
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
            log.error("[I/O OpenSearch Exception]", e);
            throw new StoreException(e.getMessage());
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
    public StoreResult deleteInternal(Collection<Object> ids, StoreOptions options) {
        String indexName = options.getIndexNameOrDefault(config.getDefaultIndexName());
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (Object id : ids) {
            bulkBuilder.operations(op -> op.delete(d -> d.index(indexName).id(id.toString())));
        }
        bulk(bulkBuilder.build());
        return StoreResult.success();
    }

    @Override
    public StoreResult updateInternal(List<Document> documents, StoreOptions options) {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        String indexName = options.getIndexNameOrDefault(config.getDefaultIndexName());
        for (Document document : documents) {
            bulkBuilder.operations(op -> op.update(
                idx -> idx.index(indexName).id(document.getId().toString()).document(document))
            );
        }
        bulk(bulkBuilder.build());
        return StoreResult.successWithIds(documents);
    }

    @Override
    public List<Document> searchInternal(SearchWrapper wrapper, StoreOptions options) {
        Double minScore = wrapper.getMinScore();
        String indexName = options.getIndexNameOrDefault(config.getDefaultIndexName());

        // https://aws.amazon.com/cn/blogs/china/use-aws-opensearch-knn-plug-in-to-implement-vector-retrieval/
        // boost 默认是 1，小于 1 会降低相关性: https://opensearch.org/docs/latest/query-dsl/specialized/script-score/#parameters
        ScriptScoreQuery scriptScoreQuery = ScriptScoreQuery.of(q -> q.minScore(minScore == null ? 0 : minScore.floatValue())
            .query(Query.of(qu -> qu.matchAll(m -> m)))
            .script(s -> s.inline(InlineScript.of(i -> i
                .source("knn_score")
                .lang("knn")
                .params("field", JsonData.of("vector"))
                .params("query_value", JsonData.of(wrapper.getVector()))
                .params("space_type", JsonData.of("cosinesimil"))
            )))
            .boost(0.5f));

        try {
            SearchResponse<Document> response = client.search(
                SearchRequest.of(s -> s.index(indexName)
                    .query(n -> n.scriptScore(scriptScoreQuery))
                    .size(wrapper.getMaxResults())),
                Document.class
            );
            return response.hits().hits().stream()
                .filter(s -> s.source() != null)
                .map(s -> {
                    Document source = s.source();
                    source.addMetadata("_score", s.score());
                    return source;
                })
                .collect(toList());
        } catch (IOException e) {
            log.error("[I/O OpenSearch Exception]", e);
            throw new StoreException(e.getMessage());
        }
    }
}

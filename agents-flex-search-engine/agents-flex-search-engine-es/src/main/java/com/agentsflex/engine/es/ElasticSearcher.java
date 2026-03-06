package com.agentsflex.engine.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agentsflex.core.document.Document;
import com.agentsflex.search.engine.service.DocumentSearcher;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;

public class ElasticSearcher implements DocumentSearcher {

    private static final Logger LOG = LoggerFactory.getLogger(ElasticSearcher.class);

    private final ESConfig esConfig;

    public ElasticSearcher(ESConfig esConfig) {
        this.esConfig = esConfig;
    }

    // 忽略 SSL 的 client 构建逻辑
    private RestClient buildRestClient() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
            AuthScope.ANY,
            new UsernamePasswordCredentials(esConfig.getUserName(), esConfig.getPassword()));

        return RestClient.builder(HttpHost.create(esConfig.getHost()))
            .setHttpClientConfigCallback(httpClientBuilder -> {
                httpClientBuilder.setSSLContext(sslContext);
                httpClientBuilder.setSSLHostnameVerifier((hostname, session) -> true);
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                return httpClientBuilder;
            })
            .build();
    }


    /**
     * 添加文档到Elasticsearch
     */
    @Override
    public boolean addDocument(Document document) {
        if (document == null || document.getContent() == null) {
            return false;
        }

        RestClient restClient = null;
        ElasticsearchTransport transport = null;
        try {
            restClient = buildRestClient();
            transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            ElasticsearchClient client = new ElasticsearchClient(transport);

            Map<String, Object> source = new HashMap<>();
            source.put("id", document.getId());
            source.put("content", document.getContent());
            if (document.getTitle() != null) {
                source.put("title", document.getTitle());
            }
            if (document.getMetadataMap() != null && !document.getMetadataMap().isEmpty()) {
                source.put("metadataMap", document.getMetadataMap());
            }

            String documentId = document.getId().toString();
            IndexOperation<?> indexOp = IndexOperation.of(i -> i
                .index(esConfig.getIndexName())
                .id(documentId)
                .document(JsonData.of(source))
            );

            BulkOperation bulkOp = BulkOperation.of(b -> b.index(indexOp));
            BulkRequest request = BulkRequest.of(b -> b.operations(Collections.singletonList(bulkOp)));
            BulkResponse response = client.bulk(request);
            return !response.errors();

        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return false;
        } finally {
            closeResources(transport, restClient);
        }
    }

    @Override
    public List<Document> searchDocuments(String keyword, int count) {
        return searchDocuments(keyword, count, null);
    }

    @Override
    public List<Document> searchDocuments(String keyword, int count, Map<String, Object> metadataFilters) {
        RestClient restClient = null;
        ElasticsearchTransport transport = null;

        try {
            restClient = buildRestClient();
            transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            ElasticsearchClient client = new ElasticsearchClient(transport);

            SearchRequest request = SearchRequest.of(s -> s
                .index(esConfig.getIndexName())
                .size(count)
                .query(q -> q
                    .bool(b -> {
                        if (keyword == null || keyword.trim().isEmpty()) {
                            b.must(m -> m.matchAll(ma -> ma));
                        } else {
                            b.must(m -> m.multiMatch(mm -> mm
                                .query(keyword)
                                .fields("title", "content")
                            ));
                        }
                        appendMetadataFilters(b, metadataFilters);
                        return b;
                    })
                )
            );

            SearchResponse<Document> response = client.search(request, Document.class);
            List<Document> results = new ArrayList<>();
            response.hits().hits().forEach(hit -> results.add(hit.source()));
            return results;

        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            return Collections.emptyList();
        } finally {
            closeResources(transport, restClient);
        }
    }

    @Override
    public boolean deleteDocument(Object id) {
        if (id == null) {
            return false;
        }

        RestClient restClient = null;
        ElasticsearchTransport transport = null;
        try {
            restClient = buildRestClient();
            transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            ElasticsearchClient client = new ElasticsearchClient(transport);

            DeleteRequest request = DeleteRequest.of(d -> d
                .index(esConfig.getIndexName())
                .id(id.toString())
            );

            DeleteResponse response = client.delete(request);
            return response.result() == co.elastic.clients.elasticsearch._types.Result.Deleted;

        } catch (Exception e) {
            LOG.error("Error deleting document with id: " + id, e);
            return false;
        } finally {
            closeResources(transport, restClient);
        }
    }

    @Override
    public boolean updateDocument(Document document) {
        if (document == null || document.getId() == null) {
            return false;
        }

        RestClient restClient = null;
        ElasticsearchTransport transport = null;

        try {
            restClient = buildRestClient();
            transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            ElasticsearchClient client = new ElasticsearchClient(transport);

            Map<String, Object> source = new HashMap<>();
            source.put("id", document.getId());
            source.put("content", document.getContent());
            if (document.getTitle() != null) {
                source.put("title", document.getTitle());
            }
            if (document.getMetadataMap() != null && !document.getMetadataMap().isEmpty()) {
                source.put("metadataMap", document.getMetadataMap());
            }

            IndexRequest<JsonData> request = IndexRequest.of(u -> u
                .index(esConfig.getIndexName())
                .id(document.getId().toString())
                .document(JsonData.of(source))
            );

            IndexResponse response = client.index(request);
            return response.result() == co.elastic.clients.elasticsearch._types.Result.Updated
                || response.result() == co.elastic.clients.elasticsearch._types.Result.Created;
        } catch (Exception e) {
            LOG.error("Error updating document with id: " + document.getId(), e);
            return false;
        } finally {
            closeResources(transport, restClient);
        }
    }

    private void appendMetadataFilters(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder boolBuilder,
                                       Map<String, Object> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : metadataFilters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.trim().isEmpty() || value == null) {
                continue;
            }

            String field = "metadataMap." + key + ".keyword";
            boolBuilder.filter(f -> f.term(t -> t.field(field).value(String.valueOf(value))));
        }
    }


    private void closeResources(AutoCloseable... closeables) {
        for (AutoCloseable closeable : closeables) {
            try {
                if (closeable != null)
                    closeable.close();
            } catch (Exception e) {
                LOG.error("Error closing resource", e);
            }
        }
    }
}

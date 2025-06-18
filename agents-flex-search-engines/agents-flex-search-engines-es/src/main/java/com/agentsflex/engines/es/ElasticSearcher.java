package com.agentsflex.engines.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agentsflex.core.document.Document;
import com.agentsflex.search.engines.config.SearcherConfig;
import com.agentsflex.search.engines.service.DocumentSearcher;
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
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;

public class ElasticSearcher implements DocumentSearcher {
    protected Logger Log = LoggerFactory.getLogger(ElasticSearcher.class);

    private String host;
    private String userName;
    private String password;
    private String indexName;

    private final ElasticsearchClient client;
    private final ElasticsearchTransport transport;
    private final RestClient restClient;

    public ElasticSearcher(SearcherConfig searcherConfig)  {
        if (searcherConfig.getHost().isEmpty()){
            Log.error("elasticSearch host 不能为空");
        }
        host = searcherConfig.getHost();
        userName = searcherConfig.getUserName();
        password = searcherConfig.getPassword();
        indexName = searcherConfig.getIndexName();
        try {
            this.restClient = buildRestClient();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (KeyManagementException e) {
            throw new RuntimeException(e);
        }
        this.transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        this.client = new ElasticsearchClient(transport);
    }

    // 忽略SSL的client构建逻辑
    private RestClient buildRestClient() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
            AuthScope.ANY,
            new UsernamePasswordCredentials(userName, password));

        HttpHost httpHost = HttpHost.create(host);

        return RestClient.builder(HttpHost.create(String.valueOf(httpHost)))
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
     * @param document 要添加的文档对象
     * @return 添加成功返回true，失败返回false
     */
    @Override
    public boolean addDocument(Document document){
        if (document == null || document.getContent() == null) {
            return false;
        }

        try {
            // 构建文档内容Map
            Map<String, Object> source = new HashMap<>();
            source.put("id", document.getId());
            source.put("content", document.getContent());

            if (document.getTitle() != null) {
                source.put("title", document.getTitle());
            }

            // 获取文档ID
            if (document.getId() == null) {
                Log.error("Document id is null");
                return false;
            }
            String documentId = document.getId().toString();
            // 构建 IndexOperation
            IndexOperation<?> indexOp = IndexOperation.of(i -> i
                .index(indexName)
                .id(documentId)
                .document(JsonData.of(source))
            );

            // 将 IndexOperation 转换为 BulkOperation
            BulkOperation bulkOp = BulkOperation.of(b -> b.index(indexOp));

            // 构建 BulkRequest
            BulkRequest request = BulkRequest.of(b -> b.operations(Collections.singletonList(bulkOp)));

            // 执行批量请求并检查结果
            BulkResponse response = client.bulk(request);
            return !response.errors();

        } catch (Exception e) {
            Log.error(e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean deleteDocument(Object id) {
        if (id == null) {
            return false;
        }

        try {
            // 使用DeleteRequest直接删除
            DeleteRequest request = DeleteRequest.of(d -> d
                .index(indexName)
                .id(id.toString())
            );

            DeleteResponse response = client.delete(request);
            return response.result() == co.elastic.clients.elasticsearch._types.Result.Deleted;
        } catch (Exception e) {
            Log.error("Error deleting document with id: " + id, e);
            return false;
        }
    }

    @Override
    public boolean updateDocument(Document document) {
        if (document == null || document.getId() == null) {
            return false;
        }

        try {
            UpdateRequest<Document, Object> request =
                UpdateRequest.of(u -> u
                    .index(indexName)
                    .id(document.getId().toString())
                    .doc(document)  // 直接使用Document对象
                );
            UpdateResponse<Document> response = client.update(request, Object.class);
            return response.result() == co.elastic.clients.elasticsearch._types.Result.Updated;
        } catch (Exception e) {
            Log.error("Error updating document with id: " + document.getId(), e);
            return false;
        }
    }

    // 搜索文档
    @Override
    public List<Document> searchDocuments(String keyWord) {
        SearchRequest request = SearchRequest.of(s -> s
            .index(indexName)
            .query(q -> q
                .match(m -> m
                    .field("title")
                    .field("content")
                    .query(keyWord)
                )
            )
        );

        SearchResponse<Document> response = null;
        try {
            response = client.search(request, Document.class);
        } catch (IOException e) {
            Log.error(e.getMessage());
            e.printStackTrace();
            return null;
        }
        List<Document> results = new ArrayList<>();
        response.hits().hits().forEach(hit -> results.add(hit.source()));
        return results;
    }



    // 关闭连接
    public void close() {
        try {
            if (transport != null) {
                transport.close();
            }
            if (restClient != null) {
                restClient.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

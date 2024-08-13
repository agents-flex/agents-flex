package com.agentsflex.store.elasticsearch;

import java.io.Serializable;

/**
 * 连接 elasticsearch 配置：<a href="https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/getting-started-java.html">elasticsearch-java</a>
 *
 * @author songyinyin
 */
public class ElasticSearchVectorStoreConfig implements Serializable {

    private String serverUrl = "https://localhost:9200";

    private String apiKey;

    private String username;

    private String password;

    private String defaultIndexName = "agents-flex-default";

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDefaultIndexName() {
        return defaultIndexName;
    }

    public void setDefaultIndexName(String defaultIndexName) {
        this.defaultIndexName = defaultIndexName;
    }
}

package com.agentsflex.store.opensearch;

import java.io.Serializable;

/**
 * 连接 open search 配置：<a href="https://opensearch.org/docs/latest/clients/java/">opensearch-java</a>
 *
 * @author songyinyin
 * @since 2024/8/10 下午8:39
 */
public class OpenSearchVectorStoreConfig implements Serializable {

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

package com.agentsflex.spring.boot.store.opensearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author songyinyin
 * @since 2024/8/13 上午11:25
 */
@ConfigurationProperties(prefix = "agents-flex.store.opensearch")
public class OpenSearchProperties {

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

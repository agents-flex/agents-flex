package com.agentsflex.spring.boot.store.qcloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author 王帅
 * @since 2024-04-10
 */
@ConfigurationProperties(prefix = "agents-flex.store.qcloud")
public class QCloudProperties {

    private String host;
    private String apiKey;
    private String account;
    private String database;
    private String defaultCollectionName;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    public void setDefaultCollectionName(String defaultCollectionName) {
        this.defaultCollectionName = defaultCollectionName;
    }

}

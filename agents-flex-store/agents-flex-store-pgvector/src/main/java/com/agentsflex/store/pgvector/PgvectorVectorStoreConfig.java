package com.agentsflex.store.pgvector;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * postgreSQL访问配置
 * https://github.com/pgvector/pgvector
 */
public class PgvectorVectorStoreConfig implements DocumentStoreConfig {
    private String host;
    private int port = 5432;
    private String databaseName = "agent_vector";
    private String username;
    private String password;
    private Map<String, String> properties = new HashMap<>();
    private String defaultCollectionName;
    private boolean autoCreateCollection = true;
    private boolean useHnswIndex = false;
    private int vectorDimension = 1024;

    public PgvectorVectorStoreConfig() {
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    public void setDefaultCollectionName(String defaultCollectionName) {
        this.defaultCollectionName = defaultCollectionName;
    }

    public boolean isAutoCreateCollection() {
        return autoCreateCollection;
    }

    public void setAutoCreateCollection(boolean autoCreateCollection) {
        this.autoCreateCollection = autoCreateCollection;
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

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public boolean checkAvailable() {
        return StringUtil.hasText(this.host, this.username, this.password, this.databaseName);
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public void setVectorDimension(int vectorDimension) {
        this.vectorDimension = vectorDimension;
    }

    public boolean isUseHnswIndex() {
        return useHnswIndex;
    }

    public void setUseHnswIndex(boolean useHnswIndex) {
        this.useHnswIndex = useHnswIndex;
    }
}

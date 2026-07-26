/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.mariadb;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/** MariaDB 连接、集合建表和向量检索配置。 */
public class MariaDBVectorStoreConfig implements DocumentStoreConfig {

    private String host;
    private int port = 3306;
    private String databaseName = "agent_vector";
    private String username;
    private String password;
    private String defaultCollectionName;
    private int vectorDimension = 1024;
    private boolean autoCreateCollection = true;
    private boolean useVectorIndex = true;
    private MariaDBDistanceType distanceType = MariaDBDistanceType.COSINE;
    private Map<String, String> properties = new LinkedHashMap<>();

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
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

    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    public void setDefaultCollectionName(String defaultCollectionName) {
        this.defaultCollectionName = defaultCollectionName;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public void setVectorDimension(int vectorDimension) {
        this.vectorDimension = vectorDimension;
    }

    public boolean isAutoCreateCollection() {
        return autoCreateCollection;
    }

    public void setAutoCreateCollection(boolean autoCreateCollection) {
        this.autoCreateCollection = autoCreateCollection;
    }

    public boolean isUseVectorIndex() {
        return useVectorIndex;
    }

    public void setUseVectorIndex(boolean useVectorIndex) {
        this.useVectorIndex = useVectorIndex;
    }

    public MariaDBDistanceType getDistanceType() {
        return distanceType;
    }

    public void setDistanceType(MariaDBDistanceType distanceType) {
        this.distanceType = distanceType;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public boolean checkAvailable() {
        return StringUtil.allHasText(host, databaseName, username, password, defaultCollectionName)
            && port > 0 && port <= 65535
            && vectorDimension > 0
            && distanceType != null;
    }
}

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
package com.agentsflex.store.chroma;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Chroma 向量存储配置，包括服务地址、集合、租户、数据库及可选 API Key。
 */
public class ChromaVectorStoreConfig implements DocumentStoreConfig {
    private static final Logger logger = LoggerFactory.getLogger(ChromaVectorStoreConfig.class);

    private String host = "localhost";
    private int port = 8000;
    private String collectionName = "agents-flex-store";
    private boolean autoCreateCollection = true;
    private String apiKey;
    private String tenant = "default_tenant";
    private String database = "default_database";

    public ChromaVectorStoreConfig() {
    }

    /**
     * 返回 Chroma 服务主机名。
     */
    public String getHost() {
        return host;
    }

    /**
     * 设置 Chroma 服务主机名。
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * 返回 Chroma 服务端口。
     */
    public int getPort() {
        return port;
    }

    /**
     * 设置 Chroma 服务端口。
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * 返回默认集合名。
     */
    public String getCollectionName() {
        return collectionName;
    }

    /**
     * 设置默认集合名。
     */
    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    /**
     * 返回是否自动创建缺失的集合。
     */
    public boolean isAutoCreateCollection() {
        return autoCreateCollection;
    }

    /**
     * 设置是否自动创建缺失的集合。
     */
    public void setAutoCreateCollection(boolean autoCreateCollection) {
        this.autoCreateCollection = autoCreateCollection;
    }

    /**
     * 返回 API Key。
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 设置 API Key。
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 返回租户名。
     */
    public String getTenant() {
        return tenant;
    }

    /**
     * 设置租户名。
     */
    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    /**
     * 返回数据库名。
     */
    public String getDatabase() {
        return database;
    }

    /**
     * 设置数据库名。
     */
    public void setDatabase(String database) {
        this.database = database;
    }

    @Override
    public boolean checkAvailable() {
        try {
            URL url = new URL(getBaseUrl() + "/api/v2/heartbeat");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (apiKey != null && !apiKey.isEmpty()) {
                connection.setRequestProperty("X-Chroma-Token", apiKey);
            }

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            return responseCode == 200;
        } catch (IOException e) {
            logger.warn("Chroma database is not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 根据主机名和端口生成 HTTP 基础地址。
     */
    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }
}

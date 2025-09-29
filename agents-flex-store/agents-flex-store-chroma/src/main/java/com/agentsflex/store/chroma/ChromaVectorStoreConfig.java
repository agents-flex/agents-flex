/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
 * ChromaVectorStoreConfig class provides configuration for ChromaVectorStore.
 */
public class ChromaVectorStoreConfig implements DocumentStoreConfig {
    private static final Logger logger = LoggerFactory.getLogger(ChromaVectorStoreConfig.class);
    
    private String host = "localhost";
    private int port = 8000;
    private String collectionName;
    private boolean autoCreateCollection = true;
    private String apiKey;
    private String tenant;
    private String database;

    public ChromaVectorStoreConfig() {
    }

    /**
     * Get the host of Chroma database
     *
     * @return the host of Chroma database
     */
    public String getHost() {
        return host;
    }

    /**
     * Set the host of Chroma database
     *
     * @param host the host of Chroma database
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * Get the port of Chroma database
     *
     * @return the port of Chroma database
     */
    public int getPort() {
        return port;
    }

    /**
     * Set the port of Chroma database
     *
     * @param port the port of Chroma database
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Get the collection name of Chroma database
     *
     * @return the collection name of Chroma database
     */
    public String getCollectionName() {
        return collectionName;
    }

    /**
     * Set the collection name of Chroma database
     *
     * @param collectionName the collection name of Chroma database
     */
    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    /**
     * Get whether to automatically create the collection if it doesn't exist
     *
     * @return true if the collection should be created automatically, false otherwise
     */
    public boolean isAutoCreateCollection() {
        return autoCreateCollection;
    }

    /**
     * Set whether to automatically create the collection if it doesn't exist
     *
     * @param autoCreateCollection true if the collection should be created automatically, false otherwise
     */
    public void setAutoCreateCollection(boolean autoCreateCollection) {
        this.autoCreateCollection = autoCreateCollection;
    }

    /**
     * Get the API key of Chroma database
     *
     * @return the API key of Chroma database
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Set the API key of Chroma database
     *
     * @param apiKey the API key of Chroma database
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Get the tenant of Chroma database
     *
     * @return the tenant of Chroma database
     */
    public String getTenant() {
        return tenant;
    }

    /**
     * Set the tenant of Chroma database
     *
     * @param tenant the tenant of Chroma database
     */
    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    /**
     * Get the database of Chroma database
     *
     * @return the database of Chroma database
     */
    public String getDatabase() {
        return database;
    }

    /**
     * Set the database of Chroma database
     *
     * @param database the database of Chroma database
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
     * Get the base URL of Chroma database
     *
     * @return the base URL of Chroma database
     */
    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }
}
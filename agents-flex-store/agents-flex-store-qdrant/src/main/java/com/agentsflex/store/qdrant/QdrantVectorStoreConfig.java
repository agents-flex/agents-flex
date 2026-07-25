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
package com.agentsflex.store.qdrant;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

/**
 * Qdrant 向量存储配置。
 *
 * <p>URI 默认使用 gRPC 端口 6334；配置 CA 文件时启用 TLS，API Key 为可选项。</p>
 */
public class QdrantVectorStoreConfig implements DocumentStoreConfig {

    private String uri = "localhost:6334";
    private String caPath;
    private String defaultCollectionName = "agents-flex-store";
    private String apiKey;
    private boolean autoCreateCollection = true;

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getCaPath() {
        return caPath;
    }

    public void setCaPath(String caPath) {
        this.caPath = caPath;
    }

    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    public void setDefaultCollectionName(String defaultCollectionName) {
        this.defaultCollectionName = defaultCollectionName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isAutoCreateCollection() {
        return autoCreateCollection;
    }

    public void setAutoCreateCollection(boolean autoCreateCollection) {
        this.autoCreateCollection = autoCreateCollection;
    }

    @Override
    public boolean checkAvailable() {
        if (!StringUtil.hasText(this.uri)) {
            return false;
        }
        QdrantVectorStore store = null;
        try {
            store = new QdrantVectorStore(this);
            store.getClient().listCollectionsAsync().get();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (store != null) {
                store.close();
            }
        }
    }
}

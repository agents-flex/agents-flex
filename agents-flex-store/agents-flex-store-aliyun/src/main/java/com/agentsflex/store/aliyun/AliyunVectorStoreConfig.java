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
package com.agentsflex.store.aliyun;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

/**
 * 阿里云 DashVector 连接配置，包括 Cluster Endpoint、API Key、超时时间和默认集合。
 */
public class AliyunVectorStoreConfig implements DocumentStoreConfig {
    private String endpoint;
    private String apiKey;
    /**
     * DashVector Java SDK 的请求超时时间，单位为秒。
     */
    private Float timeout = 10.0f;
    /**
     * DashVector 以 Cluster 和 Collection 组织数据，当前 Java SDK 不使用 Database。
     * 保留该字段仅用于兼容旧配置。
     */
    @Deprecated
    private String database;
    private String defaultCollectionName;


    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Float getTimeout() {
        return timeout;
    }

    public void setTimeout(Float timeout) {
        this.timeout = timeout;
    }

    @Deprecated
    public String getDatabase() {
        return database;
    }

    @Deprecated
    public void setDatabase(String database) {
        this.database = database;
    }

    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    public void setDefaultCollectionName(String defaultCollectionName) {
        this.defaultCollectionName = defaultCollectionName;
    }

    @Override
    public boolean checkAvailable() {
        return StringUtil.allHasText(this.endpoint, this.apiKey, this.defaultCollectionName)
            && this.timeout != null
            && Float.isFinite(this.timeout)
            && this.timeout > 0;
    }
}

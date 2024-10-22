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
package com.agentsflex.store.elasticsearch;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

/**
 * 连接 elasticsearch 配置：<a href="https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/getting-started-java.html">elasticsearch-java</a>
 *
 * @author songyinyin
 */
public class ElasticSearchVectorStoreConfig implements DocumentStoreConfig {

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

    @Override
    public boolean checkAvailable() {
        return StringUtil.hasText(this.serverUrl, this.apiKey, this.defaultIndexName);
    }
}

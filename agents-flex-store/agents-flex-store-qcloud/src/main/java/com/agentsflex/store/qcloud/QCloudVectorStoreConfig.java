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
package com.agentsflex.store.qcloud;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

public class QCloudVectorStoreConfig implements DocumentStoreConfig {

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

    @Override
    public boolean checkAvailable() {
        return StringUtil.hasText(this.host, this.apiKey, this.account, this.database, this.defaultCollectionName);
    }
}


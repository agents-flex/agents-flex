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
package com.agentsflex.spring.boot.store.opensearch;

import com.agentsflex.store.opensearch.OpenSearchVectorStore;
import com.agentsflex.store.opensearch.OpenSearchVectorStoreConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author songyinyin
 * @since 2024/8/13 上午11:26
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenSearchVectorStore.class)
@EnableConfigurationProperties(OpenSearchProperties.class)
public class OpenSearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenSearchVectorStore openSearchVectorStore(OpenSearchProperties properties,
                                                             @Autowired(required = false) OpenSearchClient client) {
        OpenSearchVectorStoreConfig config = new OpenSearchVectorStoreConfig();
        config.setServerUrl(properties.getServerUrl());
        config.setApiKey(properties.getApiKey());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDefaultIndexName(properties.getDefaultIndexName());
        if (client != null) {
            return new OpenSearchVectorStore(config, client);
        }
        return new OpenSearchVectorStore(config);
    }
}

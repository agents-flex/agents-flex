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
package com.agentsflex.spring.boot.store.chroma;

import com.agentsflex.store.chroma.ChromaVectorStore;
import com.agentsflex.store.chroma.ChromaVectorStoreConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chroma自动配置类
 *
 * @author agents-flex
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ChromaVectorStore.class)
@EnableConfigurationProperties(ChromaProperties.class)
public class ChromaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChromaVectorStore chromaVectorStore(ChromaProperties properties) {
        ChromaVectorStoreConfig config = new ChromaVectorStoreConfig();
        config.setHost(properties.getHost());
        config.setPort(properties.getPort());
        config.setCollectionName(properties.getCollectionName());
        config.setAutoCreateCollection(properties.isAutoCreateCollection());
        config.setApiKey(properties.getApiKey());
        config.setTenant(properties.getTenant());
        config.setDatabase(properties.getDatabase());
        
        return new ChromaVectorStore(config);
    }
}
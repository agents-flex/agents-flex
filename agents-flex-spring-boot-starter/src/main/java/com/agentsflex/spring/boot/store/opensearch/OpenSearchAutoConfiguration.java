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

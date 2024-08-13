package com.agentsflex.spring.boot.store.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.agentsflex.store.elasticsearch.ElasticSearchVectorStore;
import com.agentsflex.store.elasticsearch.ElasticSearchVectorStoreConfig;
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
@ConditionalOnClass(ElasticSearchVectorStore.class)
@EnableConfigurationProperties(ElasticSearchProperties.class)
public class ElasticSearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ElasticSearchVectorStore elasticSearchVectorStore(ElasticSearchProperties properties,
                                                             @Autowired(required = false) ElasticsearchClient client) {
        ElasticSearchVectorStoreConfig config = new ElasticSearchVectorStoreConfig();
        config.setServerUrl(properties.getServerUrl());
        config.setApiKey(properties.getApiKey());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDefaultIndexName(properties.getDefaultIndexName());
        if (client != null) {
            return new ElasticSearchVectorStore(config, client);
        }
        return new ElasticSearchVectorStore(config);
    }
}

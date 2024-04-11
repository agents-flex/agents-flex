package com.agentsflex.spring.boot.store.qcloud;

import com.agentsflex.store.qcloud.QCloudVectorStore;
import com.agentsflex.store.qcloud.QCloudVectorStoreConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author 王帅
 * @since 2024-04-10
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(QCloudVectorStore.class)
@EnableConfigurationProperties(QCloudProperties.class)
public class QCloudStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QCloudVectorStore qCloudVectorStore(QCloudProperties properties) {
        QCloudVectorStoreConfig config = new QCloudVectorStoreConfig();
        config.setHost(properties.getHost());
        config.setApiKey(properties.getApiKey());
        config.setAccount(properties.getAccount());
        config.setDatabase(properties.getDatabase());
        config.setDefaultCollectionName(properties.getDefaultCollectionName());
        return new QCloudVectorStore(config);
    }

}

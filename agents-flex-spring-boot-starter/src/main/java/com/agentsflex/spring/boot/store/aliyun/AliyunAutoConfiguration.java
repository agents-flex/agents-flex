package com.agentsflex.spring.boot.store.aliyun;

import com.agentsflex.store.aliyun.AliyunVectorStore;
import com.agentsflex.store.aliyun.AliyunVectorStoreConfig;
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
@ConditionalOnClass(AliyunVectorStore.class)
@EnableConfigurationProperties(AliyunProperties.class)
public class AliyunAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AliyunVectorStore aliyunVectorStore(AliyunProperties properties) {
        AliyunVectorStoreConfig config = new AliyunVectorStoreConfig();
        config.setApiKey(properties.getApiKey());
        config.setEndpoint(properties.getEndpoint());
        config.setDatabase(properties.getDatabase());
        config.setDefaultCollectionName(properties.getDefaultCollectionName());
        return new AliyunVectorStore(config);
    }

}

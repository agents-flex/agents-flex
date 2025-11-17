package com.agentsflex.spring.boot.llm.spark;

import com.agentsflex.llm.spark.SparkChatModel;
import com.agentsflex.llm.spark.SparkChatConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agents-Flex 大语言模型自动配置。
 *
 * @author 王帅
 * @since 2024-04-10
 */
@ConditionalOnClass(SparkChatModel.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SparkProperties.class)
public class SparkAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SparkChatModel sparkLlm(SparkProperties properties) {
        SparkChatConfig config = new SparkChatConfig();
        config.setAppId(properties.getAppId());
        config.setApiKey(properties.getApiKey());
        config.setApiSecret(properties.getApiSecret());
        config.setVersion(properties.getVersion());
        return new SparkChatModel(config);
    }

}

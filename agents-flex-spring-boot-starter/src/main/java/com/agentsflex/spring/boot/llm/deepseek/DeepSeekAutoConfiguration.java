package com.agentsflex.spring.boot.llm.deepseek;

import com.agentsflex.llm.deepseek.DeepseekConfig;
import com.agentsflex.llm.deepseek.DeepseekChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agents-Flex 大语言模型自动配置。
 * DeepSeek
 */
@ConditionalOnClass(DeepseekChatModel.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeepseekChatModel deepseekLlm(DeepSeekProperties properties) {
        DeepseekConfig config = new DeepseekConfig();
        config.setModel(properties.getModel());
        config.setEndpoint(properties.getEndpoint());
        config.setApiKey(properties.getApiKey());
        return new DeepseekChatModel(config);
    }

}

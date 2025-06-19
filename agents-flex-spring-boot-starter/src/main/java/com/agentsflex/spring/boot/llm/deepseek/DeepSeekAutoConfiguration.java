package com.agentsflex.spring.boot.llm.deepseek;

import com.agentsflex.llm.deepseek.DeepseekConfig;
import com.agentsflex.llm.deepseek.DeepseekLlm;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agents-Flex 大语言模型自动配置。
 * DeepSeek
 */
@ConditionalOnClass(DeepseekLlm.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeepseekLlm deepseekLlm(DeepSeekProperties properties) {
        DeepseekConfig config = new DeepseekConfig();
        config.setModel(properties.getModel());
        config.setEndpoint(properties.getEndpoint());
        config.setApiKey(properties.getApiKey());
        config.setApiSecret(properties.getApiSecret());
        return new DeepseekLlm(config);
    }

}

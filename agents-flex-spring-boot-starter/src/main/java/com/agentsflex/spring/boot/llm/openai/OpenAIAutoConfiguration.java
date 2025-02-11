package com.agentsflex.spring.boot.llm.openai;

import com.agentsflex.llm.openai.OpenAILlm;
import com.agentsflex.llm.openai.OpenAILlmConfig;
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
@ConditionalOnClass(OpenAILlm.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAIProperties.class)
public class OpenAIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenAILlm openAILlm(OpenAIProperties properties) {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey(properties.getApiKey());
        config.setApiSecret(properties.getApiSecret());
        config.setEndpoint(properties.getEndpoint());
        config.setModel(properties.getModel());
        return new OpenAILlm(config);
    }

}

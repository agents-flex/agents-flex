package com.agentsflex.spring.boot.llm.openai;

import com.agentsflex.llm.openai.OpenAILLM;
import com.agentsflex.llm.openai.OpenAILLMConfig;
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
@ConditionalOnClass(OpenAILLM.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenAILLM openAiLlm(OpenAiProperties properties) {
        OpenAILLMConfig config = new OpenAILLMConfig();
        config.setApiKey(properties.getApiKey());
        config.setApiSecret(properties.getApiSecret());
        config.setEndpoint(properties.getEndpoint());
        config.setModel(properties.getModel());
        return new OpenAILLM(config);
    }

}

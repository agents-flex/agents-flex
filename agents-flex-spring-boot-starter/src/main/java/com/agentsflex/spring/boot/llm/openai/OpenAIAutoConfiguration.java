package com.agentsflex.spring.boot.llm.openai;

import com.agentsflex.llm.openai.OpenAIChatModel;
import com.agentsflex.llm.openai.OpenAIChatConfig;
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
@ConditionalOnClass(OpenAIChatModel.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAIProperties.class)
public class OpenAIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenAIChatModel openAILlm(OpenAIProperties properties) {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey(properties.getApiKey());
        config.setEndpoint(properties.getEndpoint());
        config.setModel(properties.getModel());
        config.setRequestPath(properties.getRequestPath());
        return new OpenAIChatModel(config);
    }

}

package com.agentsflex.spring.boot.llm.chatglm;

import com.agentsflex.llm.chatglm.ChatglmLLM;
import com.agentsflex.llm.chatglm.ChatglmLlmConfig;
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
@ConditionalOnClass(ChatglmLLM.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatglmProperties.class)
public class ChatglmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatglmLLM chatglmLlm(ChatglmProperties properties) {
        ChatglmLlmConfig config = new ChatglmLlmConfig();
        config.setApiKey(properties.getApiKey());
        config.setApiSecret(properties.getApiSecret());
        config.setEndpoint(properties.getEndpoint());
        config.setModel(properties.getModel());
        return new ChatglmLLM(config);
    }

}

package com.agentsflex.spring.boot.llm.chatglm;

import com.agentsflex.llm.chatglm.ChatglmChatModel;
import com.agentsflex.llm.chatglm.ChatglmChatConfig;
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
@ConditionalOnClass(ChatglmChatModel.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatglmProperties.class)
public class ChatglmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatglmChatModel chatglmLlm(ChatglmProperties properties) {
        ChatglmChatConfig config = new ChatglmChatConfig();
        config.setApiKey(properties.getApiKey());
        config.setApiSecret(properties.getApiSecret());
        config.setEndpoint(properties.getEndpoint());
        config.setModel(properties.getModel());
        return new ChatglmChatModel(config);
    }

}

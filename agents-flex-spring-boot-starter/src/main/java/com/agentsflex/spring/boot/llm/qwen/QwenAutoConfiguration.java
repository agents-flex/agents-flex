package com.agentsflex.spring.boot.llm.qwen;

import com.agentsflex.llm.qwen.QwenLlm;
import com.agentsflex.llm.qwen.QwenLlmConfig;
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
@ConditionalOnClass(QwenLlm.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QwenProperties.class)
public class QwenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QwenLlm qwenLlm(QwenProperties properties) {
        QwenLlmConfig config = new QwenLlmConfig();
        config.setApiKey(properties.getApiKey());
        config.setApiSecret(properties.getApiSecret());
        config.setEndpoint(properties.getEndpoint());
        config.setModel(properties.getModel());
        return new QwenLlm(config);
    }

}

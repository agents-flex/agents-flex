package com.agentsflex.spring.boot.llm.ollama;

import com.agentsflex.llm.ollama.OllamaLlm;
import com.agentsflex.llm.ollama.OllamaLlmConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agents-Flex Ollama自动配置。
 *
 * @author hustlelr
 * @since 2025-02-11
 */
@ConditionalOnClass(OllamaLlm.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaProperties.class)
public class OllamaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OllamaLlm ollamaLlm(OllamaProperties properties) {
        OllamaLlmConfig config = new OllamaLlmConfig();
        config.setApiKey(properties.getApiKey());
        config.setEndpoint(properties.getEndpoint());
        config.setModel(properties.getModel());
        config.setEnableThinking(properties.getThink());
        return new OllamaLlm(config);
    }

}

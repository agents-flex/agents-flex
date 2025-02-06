package com.agentsflex.spring.boot.llm.moonshot;

import com.agentsflex.llm.moonshot.MoonshotLlm;
import com.agentsflex.llm.moonshot.MoonshotLlmConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agents-Flex 月之暗面大语言模型自动配置。
 *
 * @author lidong
 * @since 2024-06-25
 */
@ConditionalOnClass(MoonshotLlm.class)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MoonshotProperties.class)
public class MoonshotAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MoonshotLlm moonshotLlm(MoonshotProperties properties) {
        MoonshotLlmConfig config = new MoonshotLlmConfig();
        config.setApiKey(properties.getApiKey());
        config.setEndpoint(properties.getEndpoint());
        config.setModel(properties.getModel());
        return new MoonshotLlm(config);
    }

}

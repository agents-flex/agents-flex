package com.agentsflex.springboot.graph.nebula;

import com.agentsflex.graph.GraphStore;

import com.agentsflex.graph.nebula.NebulaGraphStore;
import com.agentsflex.graph.nebula.NebulaGraphStoreConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Nebula GraphStore 的 Spring Boot 自动配置。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(NebulaGraphStore.class)
@ConditionalOnProperty(prefix = "agents-flex.graph.nebula", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NebulaGraphProperties.class)
public class NebulaGraphAutoConfiguration {
    /**
     * 创建 Nebula 存储 Bean。
     *
     * <p>配置默认关闭，且只在 Nebula 实现位于 classpath 时装配；应用自定义同类型 Bean
     * 会优先于该自动配置。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public NebulaGraphStore nebulaGraphStore(NebulaGraphProperties properties) {
        NebulaGraphStoreConfig config = new NebulaGraphStoreConfig()
            .setHost(properties.getHost())
            .setPort(properties.getPort())
            .setUsername(properties.getUsername())
            .setPassword(properties.getPassword())
            .setDefaultSpace(properties.getDefaultSpace())
            .setMinSessions(properties.getMinSessions())
            .setMaxSessions(properties.getMaxSessions());
        return new NebulaGraphStore(config);
    }
}

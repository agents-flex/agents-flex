package com.agentsflex.springboot.graph.neo4j;

import com.agentsflex.graph.GraphStore;

import com.agentsflex.graph.neo4j.Neo4jGraphStore;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Neo4j GraphStore 的 Spring Boot 自动配置。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Neo4jGraphStore.class)
@ConditionalOnProperty(prefix = "agents-flex.graph.neo4j", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(Neo4jGraphProperties.class)
public class Neo4jGraphAutoConfiguration {
    /**
     * 创建 Neo4j 存储 Bean。
     *
     * <p>仅在 classpath 存在 Neo4j 驱动且显式设置 enabled=true 时生效；如果应用已经
     * 提供同类型 Bean，则保留应用自定义实例。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public Neo4jGraphStore neo4jGraphStore(Neo4jGraphProperties properties) {
        Neo4jGraphStoreConfig config = new Neo4jGraphStoreConfig()
            .setUri(properties.getUri())
            .setUsername(properties.getUsername())
            .setPassword(properties.getPassword())
            .setDefaultSpace(properties.getDefaultSpace());
        return new Neo4jGraphStore(config);
    }
}

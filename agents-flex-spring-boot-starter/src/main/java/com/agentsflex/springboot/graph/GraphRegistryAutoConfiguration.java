package com.agentsflex.springboot.graph;

import com.agentsflex.graph.connection.GraphConnectionRegistry;
import com.agentsflex.graph.GraphStore;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Graph 多连接注册表的 Spring Boot 自动配置。
 *
 * <p>Neo4j 和 Nebula 可以同时启用，注册表使用 Spring Bean 名称作为连接名，
 * 从而避免业务代码直接按 {@link GraphStore} 类型注入时产生歧义。连接的实际生命周期
 * 仍由 Spring 容器管理，因此注册表不会在销毁时重复关闭存储。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({GraphStore.class, GraphConnectionRegistry.class})
@AutoConfigureAfter(name = {
    "com.agentsflex.springboot.graph.neo4j.Neo4jGraphAutoConfiguration",
    "com.agentsflex.springboot.graph.nebula.NebulaGraphAutoConfiguration"
})
public class GraphRegistryAutoConfiguration {
    /**
     * 收集当前容器中的全部图存储并建立名称索引。
     *
     * @param stores Spring 按 Bean 名称组织的所有 {@link GraphStore}
     * @return 不拥有存储生命周期的连接注册表
     */
    @Bean
    @ConditionalOnMissingBean(GraphConnectionRegistry.class)
    public GraphConnectionRegistry graphConnectionRegistry(Map<String, GraphStore> stores) {
        GraphConnectionRegistry registry = new GraphConnectionRegistry(false);
        for (Map.Entry<String, GraphStore> entry : stores.entrySet()) {
            registry.register(entry.getKey(), entry.getValue());
        }
        return registry;
    }
}

package com.agentsflex.springboot.graph.neo4j;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Neo4j Graph 模块的 Spring Boot 配置属性。 */
@ConfigurationProperties(prefix = "agents-flex.graph.neo4j")
public class Neo4jGraphProperties {
    /** 是否启用 Neo4j 自动配置，默认关闭。 */
    private boolean enabled;
    /** Neo4j 驱动 URI。 */
    private String uri = "bolt://localhost:7687";
    /** 登录用户名。 */
    private String username = "neo4j";
    /** 登录密码。 */
    private String password;
    /** 默认数据库名。 */
    private String defaultSpace = "neo4j";

    /** @return 是否启用 */
    public boolean isEnabled() { return enabled; }
    /** 设置是否启用。 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** @return 驱动 URI */
    public String getUri() { return uri; }
    /** 设置驱动 URI。 */
    public void setUri(String uri) { this.uri = uri; }
    /** @return 用户名 */
    public String getUsername() { return username; }
    /** 设置用户名。 */
    public void setUsername(String username) { this.username = username; }
    /** @return 密码 */
    public String getPassword() { return password; }
    /** 设置密码。 */
    public void setPassword(String password) { this.password = password; }
    /** @return 默认数据库 */
    public String getDefaultSpace() { return defaultSpace; }
    /** 设置默认数据库。 */
    public void setDefaultSpace(String defaultSpace) { this.defaultSpace = defaultSpace; }
}

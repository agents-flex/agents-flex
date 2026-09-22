package com.agentsflex.springboot.graph.nebula;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Nebula Graph 模块的 Spring Boot 配置属性。 */
@ConfigurationProperties(prefix = "agents-flex.graph.nebula")
public class NebulaGraphProperties {
    /** 是否启用 Nebula 自动配置，默认关闭。 */
    private boolean enabled;
    /** Graph service 主机。 */
    private String host = "127.0.0.1";
    /** Graph service 端口。 */
    private int port = 9669;
    /** 登录用户名。 */
    private String username = "root";
    /** 登录密码。 */
    private String password = "nebula";
    /** 默认图空间。 */
    private String defaultSpace = "agents_flex";
    /** SessionPool 初始会话数。 */
    private int minSessions = 1;
    /** SessionPool 最大会话数。 */
    private int maxSessions = 10;

    /** @return 是否启用 */
    public boolean isEnabled() { return enabled; }
    /** 设置是否启用。 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** @return Graph service 主机 */
    public String getHost() { return host; }
    /** 设置主机。 */
    public void setHost(String host) { this.host = host; }
    /** @return Graph service 端口 */
    public int getPort() { return port; }
    /** 设置端口。 */
    public void setPort(int port) { this.port = port; }
    /** @return 用户名 */
    public String getUsername() { return username; }
    /** 设置用户名。 */
    public void setUsername(String username) { this.username = username; }
    /** @return 密码 */
    public String getPassword() { return password; }
    /** 设置密码。 */
    public void setPassword(String password) { this.password = password; }
    /** @return 默认空间 */
    public String getDefaultSpace() { return defaultSpace; }
    /** 设置默认空间。 */
    public void setDefaultSpace(String defaultSpace) { this.defaultSpace = defaultSpace; }
    /** @return SessionPool 初始会话数 */
    public int getMinSessions() { return minSessions; }
    /** 设置 SessionPool 初始会话数。 */
    public void setMinSessions(int minSessions) { this.minSessions = minSessions; }
    /** @return SessionPool 最大会话数 */
    public int getMaxSessions() { return maxSessions; }
    /** 设置 SessionPool 最大会话数。 */
    public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
}

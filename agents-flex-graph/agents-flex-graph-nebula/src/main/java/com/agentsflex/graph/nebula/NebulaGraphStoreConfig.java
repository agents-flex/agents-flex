package com.agentsflex.graph.nebula;

import com.agentsflex.graph.identifier.GraphIdentifiers;

/**
 * Nebula Graph 服务地址、凭据、默认空间和连接池配置。
 */
public final class NebulaGraphStoreConfig {
    /**
     * Graph service 主机。
     */
    private String host = "127.0.0.1";
    /**
     * Graph service 端口。
     */
    private int port = 9669;
    /**
     * 登录用户名。
     */
    private String username = "root";
    /**
     * 登录密码。
     */
    private String password = "nebula";
    /**
     * 未显式指定时使用的图空间。
     */
    private String defaultSpace = "agents_flex";
    /**
     * SessionPool 初始会话数。
     */
    private int minSessions = 1;
    /**
     * SessionPool 最大会话数。
     */
    private int maxSessions = 10;

    /**
     * @return 主机
     */
    public String getHost() {
        return host;
    }

    public NebulaGraphStoreConfig setHost(String host) {
        this.host = GraphIdentifiers.requireText(host, "Nebula host");
        return this;
    }

    public int getPort() {
        return port;
    }

    public NebulaGraphStoreConfig setPort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
        this.port = port;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public NebulaGraphStoreConfig setUsername(String username) {
        this.username = GraphIdentifiers.requireText(username, "Nebula username");
        return this;
    }

    public String getPassword() {
        return password;
    }

    public NebulaGraphStoreConfig setPassword(String password) {
        this.password = GraphIdentifiers.requireText(password, "Nebula password");
        return this;
    }

    public String getDefaultSpace() {
        return GraphIdentifiers.requireValid(defaultSpace, "default space");
    }

    public NebulaGraphStoreConfig setDefaultSpace(String defaultSpace) {
        this.defaultSpace = GraphIdentifiers.requireValid(defaultSpace, "default space");
        return this;
    }

    public int getMinSessions() {
        return minSessions;
    }

    public NebulaGraphStoreConfig setMinSessions(int value) {
        if (value <= 0) throw new IllegalArgumentException("minSessions must be positive");
        this.minSessions = value;
        return this;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public NebulaGraphStoreConfig setMaxSessions(int value) {
        if (value < minSessions) throw new IllegalArgumentException("maxSessions must be >= minSessions");
        this.maxSessions = value;
        return this;
    }
}
/**
 * 设置主机。
 */
/**
 * @return 端口
 */
/** 设置端口并校验 TCP 端口范围。 */
/** @return 用户名 */
/** 设置用户名。 */
/** @return 密码 */
/** 设置密码。 */
/** @return 默认空间 */
/** 设置默认空间。 */
/** @return 最小会话数 */
/** 设置最小会话数。 */
/** @return 最大会话数 */
/** 设置最大会话数，不能小于最小会话数。 */

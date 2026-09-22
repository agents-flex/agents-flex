package com.agentsflex.graph.neo4j;

import com.agentsflex.graph.identifier.GraphIdentifiers;

/**
 * Neo4j 驱动连接和默认数据库配置。
 */
public final class Neo4jGraphStoreConfig {
    /**
     * Bolt/HTTP 驱动 URI。
     */
    private String uri = "bolt://localhost:7687";
    /**
     * 登录用户名。
     */
    private String username = "neo4j";
    /**
     * 登录密码。
     */
    private String password;
    /**
     * 未显式指定空间时使用的数据库名。
     */
    private String defaultSpace = "neo4j";

    /**
     * @return 驱动 URI
     */
    public String getUri() {
        return uri;
    }

    /**
     * 设置驱动 URI。
     */
    public Neo4jGraphStoreConfig setUri(String uri) {
        this.uri = GraphIdentifiers.requireText(uri, "Neo4j URI");
        return this;
    }

    public String getUsername() {
        return username;
    }

    public Neo4jGraphStoreConfig setUsername(String username) {
        this.username = GraphIdentifiers.requireText(username, "Neo4j username");
        return this;
    }

    public String getPassword() {
        return password;
    }

    public Neo4jGraphStoreConfig setPassword(String password) {
        this.password = GraphIdentifiers.requireText(password, "Neo4j password");
        return this;
    }

    public String getDefaultSpace() {
        return defaultSpace;
    }

    public Neo4jGraphStoreConfig setDefaultSpace(String defaultSpace) {
        this.defaultSpace = GraphIdentifiers.requireValid(defaultSpace, "default space");
        return this;
    }
}
/**
 * @return 用户名
 */
/**
 * 设置用户名。
 */
/** @return 密码 */
/** 设置密码。 */
/** @return 默认数据库 */
/** 设置默认数据库。 */

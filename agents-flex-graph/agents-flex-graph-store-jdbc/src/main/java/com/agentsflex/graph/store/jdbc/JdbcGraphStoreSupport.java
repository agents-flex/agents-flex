package com.agentsflex.graph.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * JDBC Store 的包内公共基础设施。
 *
 * <p>集中处理连接获取、表名前缀、payload 序列化、参数校验和异常包装，避免不同 Store 对这些
 * 基础规则产生不一致实现。</p>
 */
abstract class JdbcGraphStoreSupport {
    /**
     * 当前 Store 共享的不可变配置。
     */
    protected final JdbcGraphStoreConfig config;

    JdbcGraphStoreSupport(JdbcGraphStoreConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
    }

    /**
     * 拼接经过配置校验的表名前缀和固定表名后缀。
     */
    protected String table(String suffix) {
        return config.getTablePrefix() + suffix;
    }

    /**
     * 从应用提供的 DataSource 获取一次操作使用的连接。
     */
    protected Connection connection() throws SQLException {
        return config.getDataSource().getConnection();
    }

    /**
     * 将底层 SQL 异常统一包装为保留 cause 的运行时异常。
     */
    protected RuntimeException failure(String operation, SQLException error) {
        return new IllegalStateException("Failed to " + operation, error);
    }

    /**
     * 使用配置的序列化器生成二进制 payload。
     */
    protected byte[] bytes(Object value) {
        return config.serializer().serialize(value);
    }

    /**
     * 使用配置的序列化器恢复领域快照。
     */
    protected <T> T parse(byte[] value, Class<T> type) {
        return config.serializer().deserialize(value, type);
    }

    /**
     * 尽力回滚事务。该方法仅用于异常清理路径，原始业务异常优先，因此回滚异常不会覆盖它。
     */
    protected static void rollback(Connection c) {
        if (c != null) try {
            c.rollback();
        } catch (SQLException ignored) {
        }
    }

    /**
     * 校验必填字符串并返回去除首尾空白后的值。
     */
    protected static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    /**
     * 生成稳定的 SHA-256 小写十六进制摘要，用固定长度索引长名称并规避 utf8mb4 索引长度限制。
     */
    protected static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int item = digest[i] & 0xff;
                result[i * 2] = hex[item >>> 4];
                result[i * 2 + 1] = hex[item & 0x0f];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

package com.agentsflex.agent.store.redis;

import redis.clients.jedis.JedisPooled;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

/** Redis Store 共享的键生成、Lua 执行和对象序列化能力。 */
abstract class RedisAgentStoreSupport {
    protected final RedisAgentStoreConfig config;
    protected final JedisPooled jedis;

    RedisAgentStoreSupport(RedisAgentStoreConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config; this.jedis = config.jedis();
    }

    /** 使用统一 hash tag，使一次 Lua 操作涉及的键在 Redis Cluster 中落入同一槽位。 */
    protected String key(String type, String id) { return config.keyPrefix() + "{agent-store}:" + type + ":" + id; }
    protected String index(String type) { return config.keyPrefix() + "{agent-store}:" + type; }

    protected Object eval(String script, java.util.List<String> keys, java.util.List<String> args) {
        return jedis.eval(script, keys, args);
    }

    protected String encode(Serializable value) {
        return Base64.getEncoder().encodeToString(config.serializer().serialize(value));
    }

    protected <T> T decode(String value, Class<T> type) {
        if (value == null) return null;
        byte[] bytes = Base64.getDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
        return config.serializer().deserialize(bytes, type);
    }

    protected static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    protected static java.util.List<String> keys(String... values) { return java.util.Arrays.asList(values); }
    protected static java.util.List<String> args(String... values) { return java.util.Arrays.asList(values); }
    protected static java.util.List<String> noKeys() { return Collections.emptyList(); }
}

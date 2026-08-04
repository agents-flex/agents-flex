package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.store.AgentStoreSerializer;
import com.agentsflex.agent.store.FastjsonAgentStoreSerializer;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.util.Objects;

/** Redis Agent Store 的客户端和键前缀配置。 */
public final class RedisAgentStoreConfig implements AutoCloseable {
    private final JedisPooled jedis;
    private final String keyPrefix;
    private final boolean closeClient;
    private final AgentStoreSerializer serializer;

    private RedisAgentStoreConfig(Builder builder) {
        this.jedis = builder.jedis != null ? builder.jedis : new JedisPooled(URI.create(builder.uri));
        this.keyPrefix = builder.keyPrefix;
        this.closeClient = builder.jedis == null;
        this.serializer = builder.serializer;
    }

    public static Builder builder(String uri) { return new Builder(uri); }
    public static Builder builder(JedisPooled jedis) { return new Builder(jedis); }

    JedisPooled jedis() { return jedis; }
    String keyPrefix() { return keyPrefix; }
    AgentStoreSerializer serializer() { return serializer; }

    public RedisAgentRunStore runStore() { return new RedisAgentRunStore(this); }
    public RedisAgentRunCommandStore commandStore() { return new RedisAgentRunCommandStore(this); }
    public RedisAgentArtifactStore artifactStore() { return new RedisAgentArtifactStore(this); }

    @Override public void close() { if (closeClient) jedis.close(); }

    public static final class Builder {
        private String uri;
        private JedisPooled jedis;
        private String keyPrefix = "agents-flex:agent:";
        private AgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();

        private Builder(String uri) { this.uri = Objects.requireNonNull(uri, "uri must not be null"); }
        private Builder(JedisPooled jedis) { this.jedis = Objects.requireNonNull(jedis, "jedis must not be null"); }

        /** 设置所有 Agent 键的命名空间前缀。 */
        public Builder keyPrefix(String keyPrefix) {
            if (keyPrefix == null || keyPrefix.trim().isEmpty()) throw new IllegalArgumentException("keyPrefix must not be blank");
            this.keyPrefix = keyPrefix;
            return this;
        }

        /** 设置 Snapshot、Command 和 Artifact 等持久化对象的二进制编码实现。 */
        public Builder serializer(AgentStoreSerializer serializer) {
            this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
            return this;
        }

        public RedisAgentStoreConfig build() { return new RedisAgentStoreConfig(this); }
    }
}

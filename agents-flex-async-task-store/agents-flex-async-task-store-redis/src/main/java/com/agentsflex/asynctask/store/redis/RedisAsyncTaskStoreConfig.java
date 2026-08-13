/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.asynctask.store.redis;

import com.agentsflex.asynctask.store.AsyncTaskStoreSerializer;
import com.agentsflex.asynctask.store.FastjsonAsyncTaskStoreSerializer;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.util.Objects;

/**
 * Redis 异步任务 Store 的客户端、键空间与序列化配置。
 */
public final class RedisAsyncTaskStoreConfig implements AutoCloseable {
    private final JedisPooled jedis;
    private final String keyPrefix;
    private final boolean closeClient;
    private final AsyncTaskStoreSerializer serializer;

    private RedisAsyncTaskStoreConfig(Builder b) {
        jedis = b.jedis == null ? new JedisPooled(URI.create(b.uri)) : b.jedis;
        closeClient = b.jedis == null;
        keyPrefix = b.keyPrefix;
        serializer = b.serializer;
    }

    public static Builder builder(String uri) {
        return new Builder(Objects.requireNonNull(uri, "uri must not be null"));
    }

    public static Builder builder(JedisPooled jedis) {
        return new Builder(Objects.requireNonNull(jedis, "jedis must not be null"));
    }

    /**
     * 暴露客户端，便于健康检查和应用统一管理 Redis。
     */
    public JedisPooled jedis() {
        return jedis;
    }

    String keyPrefix() {
        return keyPrefix;
    }

    AsyncTaskStoreSerializer serializer() {
        return serializer;
    }

    /**
     * 创建异步任务 Store。
     */
    public RedisAsyncTaskStore store() {
        return new RedisAsyncTaskStore(this);
    }

    @Override
    public void close() {
        if (closeClient) jedis.close();
    }

    /**
     * 配置构建器。
     */
    public static final class Builder {
        private String uri;
        private JedisPooled jedis;
        private String keyPrefix = "agents-flex:async-task:";
        private AsyncTaskStoreSerializer serializer = new FastjsonAsyncTaskStoreSerializer();

        private Builder(String uri) {
            this.uri = uri;
        }

        private Builder(JedisPooled jedis) {
            this.jedis = jedis;
        }

        public Builder keyPrefix(String value) {
            if (value == null || value.trim().isEmpty())
                throw new IllegalArgumentException("keyPrefix must not be blank");
            keyPrefix = value;
            return this;
        }

        public Builder serializer(AsyncTaskStoreSerializer value) {
            serializer = Objects.requireNonNull(value, "serializer must not be null");
            return this;
        }

        public RedisAsyncTaskStoreConfig build() {
            return new RedisAsyncTaskStoreConfig(this);
        }
    }
}

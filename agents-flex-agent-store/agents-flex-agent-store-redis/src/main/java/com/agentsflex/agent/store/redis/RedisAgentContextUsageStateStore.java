package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.context.AgentContextUsageState;
import com.agentsflex.agent.context.AgentContextUsageStateStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 基于 Redis Lua 的服务端 Usage 状态 CAS 存储。
 *
 * <p>状态保存在 Redis Hash 中，version 字段用于 CAS，payload 字段保存完整序列化对象。Lua 脚本
 * 在 Redis 单线程执行边界内完成读取版本、比较和写入，避免多个 Runner 同时推进同一会话时覆盖状态。</p>
 */
public final class RedisAgentContextUsageStateStore extends RedisAgentStoreSupport
    implements AgentContextUsageStateStore {
    /**
     * 原子 CAS 脚本：不存在的 key 视为版本 0，版本匹配后一次性写入新 version 和 payload。
     */
    private static final String CAS_SAVE =
        "local v=redis.call('HGET',KEYS[1],'version'); "
            + "local actual=v and tonumber(v) or 0; "
            + "if actual~=tonumber(ARGV[1]) then return 0 end; "
            + "redis.call('HSET',KEYS[1],'version',ARGV[2],'payload',ARGV[3]); return 1";

    /**
     * 使用 Agent Store 的统一 Redis 客户端、key 前缀和序列化器。
     */
    RedisAgentContextUsageStateStore(RedisAgentStoreConfig config) {
        super(config);
    }

    /**
     * 按会话与模型作用域读取状态，不存在时返回 {@code null}。
     */
    @Override
    public AgentContextUsageState load(String conversationId, String usageScopeKey) {
        requireKey(conversationId, usageScopeKey);
        return decode(jedis.hget(stateKey(conversationId, usageScopeKey), "payload"),
            AgentContextUsageState.class);
    }

    /**
     * 执行 Lua CAS 保存；参数版本不连续属于调用错误，Redis 中版本不匹配属于正常并发冲突。
     */
    @Override
    public boolean save(AgentContextUsageState state, long expectedVersion) {
        if (state == null || expectedVersion < 0
            || state.getVersion() != expectedVersion + 1) {
            throw new IllegalArgumentException("invalid usage state save");
        }
        requireKey(state.getConversationId(), state.getUsageScopeKey());
        Object result = eval(CAS_SAVE, keys(stateKey(state.getConversationId(), state.getUsageScopeKey())),
            args(String.valueOf(expectedVersion), String.valueOf(state.getVersion()), encode(state)));
        return ((Number) result).longValue() == 1L;
    }

    /**
     * 构造 Redis 状态键。对可能较长且包含特殊字符的 scope 做 SHA-256，确保 key 长度稳定。
     */
    private String stateKey(String conversationId, String usageScopeKey) {
        return key("context-usage", conversationId + ":" + sha256(usageScopeKey));
    }

    /**
     * 将模型作用域稳定映射为小写十六进制 SHA-256。
     */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /**
     * 校验联合业务键，避免生成不可追踪的空白 Redis key。
     */
    private static void requireKey(String conversationId, String usageScopeKey) {
        if (conversationId == null || conversationId.trim().isEmpty()
            || usageScopeKey == null || usageScopeKey.trim().isEmpty()) {
            throw new IllegalArgumentException("conversationId and usageScopeKey must not be blank");
        }
    }
}

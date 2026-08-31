package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.compression.AgentContextCompressionStateStore;

/**
 * 使用 Redis Hash 保存上下文压缩状态。
 *
 * <p>版本检查和写入在同一个 Lua 脚本中完成，适用于多个应用节点并发压缩同一会话。
 * 压缩状态与 Turn Store 使用相同的 key 前缀和 Redis Cluster hash tag，但拥有独立的键空间。</p>
 */
public final class RedisAgentContextCompressionStateStore extends RedisAgentStoreSupport
    implements AgentContextCompressionStateStore {
    private static final String CAS_SAVE =
        "local v=redis.call('HGET',KEYS[1],'version'); "
            + "local actual=v and tonumber(v) or 0; "
            + "if actual~=tonumber(ARGV[1]) then return 0 end; "
            + "redis.call('HSET',KEYS[1],'version',ARGV[2],'payload',ARGV[3]); return 1";

    RedisAgentContextCompressionStateStore(RedisAgentStoreConfig config) {
        super(config);
    }

    @Override
    public AgentContextCompressionState load(String conversationId) {
        requireConversationId(conversationId);
        return decode(jedis.hget(key("compression", conversationId), "payload"),
            AgentContextCompressionState.class);
    }

    @Override
    public boolean save(String conversationId, AgentContextCompressionState state, long expectedVersion) {
        requireConversationId(conversationId);
        if (state == null) throw new IllegalArgumentException("state must not be null");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        Object result = eval(CAS_SAVE, keys(key("compression", conversationId)),
            args(String.valueOf(expectedVersion), String.valueOf(state.getVersion()), encode(state)));
        return ((Number) result).longValue() == 1L;
    }

    private static void requireConversationId(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty())
            throw new IllegalArgumentException("conversationId must not be blank");
    }
}

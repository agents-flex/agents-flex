package com.agentsflex.agent.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 Usage 状态 CAS 实现，适用于单节点应用和测试。
 *
 * <p>多节点部署应使用 JDBC 或 Redis 实现，否则不同进程之间无法共享阈值与压缩状态。</p>
 */
public final class InMemoryAgentContextUsageStateStore implements AgentContextUsageStateStore {
    /**
     * 以复合键保存各会话、各模型作用域的最新不可变状态。
     */
    private final ConcurrentMap<String, AgentContextUsageState> states = new ConcurrentHashMap<>();

    /**
     * 读取内存中的最新状态；未写入时返回 {@code null}。
     */
    @Override
    public AgentContextUsageState load(String conversationId, String usageScopeKey) {
        return states.get(key(conversationId, usageScopeKey));
    }

    /**
     * 在同一个临界区内完成版本检查和替换，确保并发写入最多只有一个成功。
     */
    @Override
    public boolean save(AgentContextUsageState state, long expectedVersion) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        String key = key(state.getConversationId(), state.getUsageScopeKey());
        synchronized (states) {
            // ConcurrentMap 只能保证单次操作线程安全；CAS 需要把读取、比较和写入合并为原子步骤。
            AgentContextUsageState current = states.get(key);
            long currentVersion = current == null ? 0 : current.getVersion();
            if (currentVersion != expectedVersion || state.getVersion() != expectedVersion + 1) return false;
            states.put(key, state);
            return true;
        }
    }

    /**
     * 使用不会出现在正常 ID 中的空字符连接复合键，避免简单字符串拼接产生歧义。
     */
    private static String key(String conversationId, String usageScopeKey) {
        if (conversationId == null || usageScopeKey == null) {
            throw new IllegalArgumentException("conversationId and usageScopeKey must not be null");
        }
        return conversationId + "\u0000" + usageScopeKey;
    }
}

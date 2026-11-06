package com.agentsflex.agent.context;

/**
 * 服务端确认 Usage 状态的 CAS 存储接口。
 *
 * <p>状态按 {@code conversationId + usageScopeKey} 隔离，避免同一会话切换 Agent 版本或模型后
 * 错误复用旧基准。</p>
 */
public interface AgentContextUsageStateStore {
    /**
     * 读取指定会话和模型作用域的状态。
     *
     * @return 尚未保存过状态时返回 {@code null}
     */
    AgentContextUsageState load(String conversationId, String usageScopeKey);

    /**
     * 使用乐观锁保存新状态。
     *
     * <p>实现必须同时校验当前持久化版本等于 {@code expectedVersion}，并且新状态版本严格等于
     * {@code expectedVersion + 1}。版本冲突返回 {@code false}，参数非法则抛出异常。</p>
     *
     * @param state           待保存的不可变新状态
     * @param expectedVersion 调用方读取到的旧版本；首次保存为 0
     * @return 保存成功返回 {@code true}，CAS 冲突返回 {@code false}
     */
    boolean save(AgentContextUsageState state, long expectedVersion);
}

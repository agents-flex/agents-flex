package com.agentsflex.agent.context;

import com.agentsflex.agent.AgentRun;

/**
 * 在模型调用前整理可持久化消息上下文。
 *
 * <p>ContextManager 可以直接压缩 AgentRun 的消息历史；返回 changed 后 Runner 会立即保存 Snapshot，
 * 保证后续恢复看到相同上下文。实现被多个 Run 复用时应自行保证线程安全。</p>
 */
@FunctionalInterface
public interface AgentContextManager {
    /**
     * 在模型调用前整理 Run 的持久化消息历史。
     *
     * @param run 即将调用模型的当前 Run
     * @return 是否修改消息以及压缩前后的统计信息
     */
    AgentContextUpdate prepare(AgentRun run);

    /**
     * 返回不修改消息历史的默认实现。
     */
    static AgentContextManager none() {
        return run -> AgentContextUpdate.unchanged();
    }
}

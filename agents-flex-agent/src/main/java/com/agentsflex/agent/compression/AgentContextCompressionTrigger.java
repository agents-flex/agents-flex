package com.agentsflex.agent.compression;

/**
 * 判断新增历史是否达到业务定义的压缩条件。
 */
@FunctionalInterface
public interface AgentContextCompressionTrigger {
    /**
     * 根据新增历史、规模统计和当前压缩状态决定是否执行本轮压缩。
     *
     * @param input 当前增量压缩输入（只读）
     * @return 需要生成并持久化新摘要时返回 {@code true}
     */
    boolean shouldCompress(AgentContextCompressionInput input);
}

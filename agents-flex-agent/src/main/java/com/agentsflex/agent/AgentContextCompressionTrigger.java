package com.agentsflex.agent;

/**
 * 判断新增历史是否达到业务定义的压缩条件。
 */
@FunctionalInterface
public interface AgentContextCompressionTrigger {
    boolean shouldCompress(AgentContextCompressionInput input);
}

package com.agentsflex.agent.compression;

import java.util.ArrayList;
import java.util.List;

/**
 * 常用的上下文压缩决策器工厂。
 */
public final class AgentContextCompressionDeciders {
    private AgentContextCompressionDeciders() {
    }

    /**
     * 始终满足条件；适合由外部流程明确决定压缩时机的场景。
     */
    public static AgentContextCompressionDecider always() {
        return input -> true;
    }

    /**
     * 永不满足条件；适合临时关闭增量语义压缩但保留策略配置的场景。
     */
    public static AgentContextCompressionDecider never() {
        return input -> false;
    }

    /**
     * 待压缩消息的估算 Token 数达到指定阈值时满足条件。
     */
    public static AgentContextCompressionDecider pendingTokensAtLeast(long threshold) {
        if (threshold < 0) throw new IllegalArgumentException("threshold must not be negative");
        return input -> input != null && input.getEstimatedPendingTokens() >= threshold;
    }

    /**
     * 待压缩 Turn 数达到指定阈值时满足条件。
     */
    public static AgentContextCompressionDecider pendingTurnsAtLeast(int threshold) {
        if (threshold < 0) throw new IllegalArgumentException("threshold must not be negative");
        return input -> input != null && input.getPendingTurnCount() >= threshold;
    }

    /**
     * 待压缩消息数达到指定阈值时满足条件。
     */
    public static AgentContextCompressionDecider pendingMessagesAtLeast(int threshold) {
        if (threshold < 0) throw new IllegalArgumentException("threshold must not be negative");
        return input -> input != null && input.getPendingMessages().size() >= threshold;
    }

    /**
     * 任一条件满足时满足条件；空条件列表始终返回 {@code false}。
     */
    public static AgentContextCompressionDecider anyOf(AgentContextCompressionDecider... deciders) {
        final List<AgentContextCompressionDecider> normalized = normalize(deciders);
        return input -> {
            for (AgentContextCompressionDecider decider : normalized) {
                if (decider.shouldCompress(input)) return true;
            }
            return false;
        };
    }

    /**
     * 全部条件满足时满足条件；空条件列表始终返回 {@code true}。
     */
    public static AgentContextCompressionDecider allOf(AgentContextCompressionDecider... deciders) {
        final List<AgentContextCompressionDecider> normalized = normalize(deciders);
        return input -> {
            for (AgentContextCompressionDecider decider : normalized) {
                if (!decider.shouldCompress(input)) return false;
            }
            return true;
        };
    }

    private static List<AgentContextCompressionDecider> normalize(
        AgentContextCompressionDecider[] deciders) {
        List<AgentContextCompressionDecider> result = new ArrayList<>();
        if (deciders != null) {
            for (AgentContextCompressionDecider decider : deciders) {
                if (decider != null) result.add(decider);
            }
        }
        return result;
    }
}

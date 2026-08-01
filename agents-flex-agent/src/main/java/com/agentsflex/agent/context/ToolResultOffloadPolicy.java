package com.agentsflex.agent.context;

/** 判断工具结果是否需要从消息历史外置。 */
@FunctionalInterface
public interface ToolResultOffloadPolicy {
    /** @return true 表示完整结果应保存到 Artifact Store */
    boolean shouldOffload(String toolName, String content);

    /** 返回永不外置工具结果的默认策略。 */
    static ToolResultOffloadPolicy disabled() { return (toolName, content) -> false; }

    /** 创建按字符数量判断是否外置的策略。 */
    static ToolResultOffloadPolicy largerThan(int characterCount) {
        if (characterCount <= 0) throw new IllegalArgumentException("characterCount must be greater than 0");
        return (toolName, content) -> content != null && content.length() > characterCount;
    }
}

package com.agentsflex.agent.tool;

import java.util.Map;

/**
 * 工具在长时间执行过程中主动上报进度的接口。
 *
 * <p>Runner 通过 {@link AgentToolContext#getProgressEmitter()} 提供实例。进度最终转换为
 * TOOL_PROGRESS 事件，只用于观察，不保存 Snapshot，也不改变工具结果或 Turn 状态。</p>
 */
@FunctionalInterface
public interface AgentToolProgressEmitter {
    /**
     * 发布一条不修改 Turn 状态的工具执行进度。
     *
     * @param message 面向观察者的简短进度描述
     * @param data 结构化进度数据，可为 {@code null}
     */
    void emit(String message, Map<String, ?> data);
}

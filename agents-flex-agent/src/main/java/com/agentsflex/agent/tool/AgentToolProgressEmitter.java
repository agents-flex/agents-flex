package com.agentsflex.agent.tool;

import java.util.Map;

/**
 * 工具在长时间执行过程中主动上报进度的接口。
 *
 * <p>Runner 以 {@link #CONTEXT_ATTRIBUTE} 为键将实例放入 ToolContext。进度最终转换为
 * TOOL_PROGRESS 事件，只用于观察，不保存 Snapshot，也不改变工具结果或 Run 状态。</p>
 */
@FunctionalInterface
public interface AgentToolProgressEmitter {
    /** 从 ToolContext 读取当前进度发布器时使用的属性键。 */
    String CONTEXT_ATTRIBUTE = AgentToolProgressEmitter.class.getName();

    /**
     * 发布一条不修改 Run 状态的工具执行进度。
     *
     * @param message 面向观察者的简短进度描述
     * @param data 结构化进度数据，可为 {@code null}
     */
    void emit(String message, Map<String, ?> data);
}

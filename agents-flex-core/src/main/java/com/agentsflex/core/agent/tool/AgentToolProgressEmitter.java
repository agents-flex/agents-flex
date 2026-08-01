package com.agentsflex.core.agent.tool;

import java.util.Map;

/** 工具在长时间执行过程中主动上报进度的接口。 */
@FunctionalInterface
public interface AgentToolProgressEmitter {
    String CONTEXT_ATTRIBUTE = AgentToolProgressEmitter.class.getName();

    /** 发布一条不修改 Run 状态的工具执行进度。 */
    void emit(String message, Map<String, ?> data);
}

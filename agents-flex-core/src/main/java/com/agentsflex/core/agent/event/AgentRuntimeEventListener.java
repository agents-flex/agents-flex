package com.agentsflex.core.agent.event;

/** 消费细粒度实时事件的监听器。 */
@FunctionalInterface
public interface AgentRuntimeEventListener {
    void onEvent(AgentRuntimeEvent event);
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.event;

/**
 * 观察 Agent 执行事件的统一监听器。
 *
 * <p>同一个 Runner 可以注册多个监听器，并可能从不同 Worker 线程为不同 Run 并发调用它们，
 * 因此有共享状态的实现需要自行保证线程安全。需要改变执行决策时应使用 AgentMiddleware。</p>
 */
@FunctionalInterface
public interface AgentEventListener {

    /**
     * 同步接收一条不可变事件。
     *
     * <p>监听器不参与执行决策，并应快速返回。Runner 会记录并隔离监听器抛出的运行时异常，
     * 可靠持久化、重试和消费游标由业务系统负责。</p>
     *
     * @param event Runner 已经创建并冻结的事件
     */
    void onEvent(AgentEvent event);
}

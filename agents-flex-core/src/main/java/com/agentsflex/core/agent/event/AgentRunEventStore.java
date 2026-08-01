/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.event;

import java.util.List;

/**
 * AgentRun 追加式事件流存储接口。
 *
 * <p>实现必须为同一 runId 原子分配严格递增的 sequence，并根据 eventId 保证重复追加不会生成
 * 第二条事件。事件读取按 sequence 升序返回。</p>
 */
public interface AgentRunEventStore {

    /** 追加事件并返回已经分配 sequence 的持久化事件。 */
    AgentRunEvent append(AgentRunEvent event);

    /**
     * 增量读取指定 Run 的事件。
     *
     * @param afterSequence 只返回 sequence 大于该值的事件，传 0 表示从头读取
     * @param limit 最大返回数量
     */
    List<AgentRunEvent> load(String runId, long afterSequence, int limit);
}

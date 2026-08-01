/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent.store;

import com.agentsflex.core.agent.AgentRunSnapshot;

/**
 * 原子创建子任务后，由 Store 返回的父子运行快照。
 *
 * <p>父快照已经进入等待子任务状态，子快照已经完成首次持久化，两者都包含 Store 分配的新版本号。</p>
 */
public final class ParentChildRunSnapshots {

    /** 已进入等待状态的父运行快照。 */
    private final AgentRunSnapshot parent;
    /** 新创建并可被 Worker 领取的子运行快照。 */
    private final AgentRunSnapshot child;

    public ParentChildRunSnapshots(AgentRunSnapshot parent, AgentRunSnapshot child) {
        this.parent = parent;
        this.child = child;
    }

    public AgentRunSnapshot getParent() { return parent; }
    public AgentRunSnapshot getChild() { return child; }
}

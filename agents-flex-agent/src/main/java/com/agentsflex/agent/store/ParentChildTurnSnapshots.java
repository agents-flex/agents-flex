/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.store;

import com.agentsflex.agent.AgentTurnSnapshot;

/**
 * 原子创建子任务后，由 Store 返回的父子 Turn 快照。
 *
 * <p>父快照已经进入等待子任务状态，子快照已经完成首次持久化，两者都包含 Store 分配的新版本号。</p>
 */
public final class ParentChildTurnSnapshots {

    /**
     * 已进入等待状态的父 Turn 快照。
     */
    private final AgentTurnSnapshot parent;
    /**
     * 新创建并可被 Worker 领取的子 Turn 快照。
     */
    private final AgentTurnSnapshot child;

    /**
     * 创建原子保存结果。
     *
     * @param parent 已进入等待状态且版本递增的父快照
     * @param child  已完成首次保存的子快照
     */
    public ParentChildTurnSnapshots(AgentTurnSnapshot parent, AgentTurnSnapshot child) {
        this.parent = parent;
        this.child = child;
    }

    /**
     * @return 已进入等待状态并分配新版本的父快照
     */
    public AgentTurnSnapshot getParent() {
        return parent;
    }

    /**
     * @return 已创建并可独立领取的子快照
     */
    public AgentTurnSnapshot getChild() {
        return child;
    }
}

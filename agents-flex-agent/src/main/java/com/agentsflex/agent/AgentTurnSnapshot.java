/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;

/**
 * AgentTurn 在稳定执行边界上的不可变持久化快照。
 *
 * <p>Snapshot 只保存用于重新加载 Agent 的标识和一份不可变 {@link AgentTurnState}。ChatModel、Tool、
 * Prompt、Throwable 等进程内对象不会进入快照。恢复时先按 agentId 和 agentVersion 加载完整 Agent，
 * 再由 State 重建 Turn。</p>
 */
public final class AgentTurnSnapshot implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String agentId;
    private final String agentVersion;
    private final AgentTurnState state;

    /**
     * 创建与具体 Agent 版本绑定的不可变 Turn 快照。
     *
     * @param agentId      Agent 稳定 ID
     * @param agentVersion Agent 配置版本
     * @param state        已冻结的 Turn 状态
     */
    private AgentTurnSnapshot(String agentId, String agentVersion, AgentTurnState state) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.state = state.immutableCopy();
    }

    /**
     * 使用 Agent 标识和完整轮次状态创建 Snapshot。
     */
    public static AgentTurnSnapshot of(String agentId, String agentVersion,
                                       AgentTurnState state) {
        validate(agentId, agentVersion, state);
        return new AgentTurnSnapshot(agentId, agentVersion, state);
    }

    /**
     * @return 与当前快照完全隔离的深拷贝
     */
    public AgentTurnSnapshot copy() {
        return new AgentTurnSnapshot(agentId, agentVersion, state);
    }

    /**
     * 返回内容相同、仅替换 Store 乐观锁版本的新快照。
     */
    public AgentTurnSnapshot withVersion(long version) {
        return withState(state.toBuilder().version(version).build());
    }

    /**
     * 返回 Agent 标识不变、轮次状态替换为指定值的新 Snapshot。
     */
    public AgentTurnSnapshot withState(AgentTurnState state) {
        validate(agentId, agentVersion, state);
        return new AgentTurnSnapshot(agentId, agentVersion, state);
    }

    /**
     * @return Snapshot 持有的不可变轮次状态
     */
    public AgentTurnState getState() {
        return state;
    }

    /**
     * @return 用于恢复完整 Agent 定义的稳定标识
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * @return 创建该 Turn 时冻结的 Agent 版本
     */
    public String getAgentVersion() {
        return agentVersion;
    }

    /**
     * 校验恢复 Agent 所需身份字段和状态对象均已提供。
     */
    private static void validate(String agentId, String agentVersion, AgentTurnState state) {
        if (!StringUtil.hasText(agentId)) {
            throw new IllegalStateException("agentId must not be blank");
        }
        if (!StringUtil.hasText(agentVersion)) {
            throw new IllegalStateException("agentVersion must not be blank");
        }
        if (state == null || state.getTurnId() == null) {
            throw new IllegalStateException("state and turnId must not be null");
        }
        if (state.getExecutionPolicy() == null) {
            throw new IllegalStateException("executionPolicy must not be null");
        }
        if (state.getStatus() == null) {
            throw new IllegalStateException("status must not be null");
        }
        if (state.getExecutionPoint() == null) {
            throw new IllegalStateException("executionPoint must not be null");
        }
    }

}

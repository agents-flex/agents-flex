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
 * AgentRun 在稳定执行边界上的不可变持久化快照。
 *
 * <p>Snapshot 只保存用于重新加载 Agent 的标识和一份不可变 {@link AgentRunState}。ChatModel、Tool、
 * Prompt、Throwable 等进程内对象不会进入快照。恢复时先按 agentId 和 agentVersion 加载完整 Agent，
 * 再由 State 重建 Run。</p>
 */
public final class AgentRunSnapshot implements Serializable {

    private static final long serialVersionUID = 2L;

    private final String agentId;
    private final String agentVersion;
    private final AgentRunState state;

    private AgentRunSnapshot(String agentId, String agentVersion, AgentRunState state) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.state = state.immutableCopy();
    }

    /**
     * 使用 Agent 标识和完整运行状态创建 Snapshot。
     */
    public static AgentRunSnapshot of(String agentId, String agentVersion,
                                      AgentRunState state) {
        validate(agentId, agentVersion, state);
        return new AgentRunSnapshot(agentId, agentVersion, state);
    }

    static AgentRunSnapshot fromState(String agentId, String agentVersion, AgentRunState state) {
        validate(agentId, agentVersion, state);
        return new AgentRunSnapshot(agentId, agentVersion, state);
    }

    /** @return 与当前快照完全隔离的深拷贝 */
    public AgentRunSnapshot copy() {
        return new AgentRunSnapshot(agentId, agentVersion, state);
    }

    /** 返回内容相同、仅替换 Store 乐观锁版本的新快照。 */
    public AgentRunSnapshot withVersion(long version) {
        return withState(state.toBuilder().version(version).build());
    }

    /**
     * 返回 Agent 标识不变、运行状态替换为指定值的新 Snapshot。
     */
    public AgentRunSnapshot withState(AgentRunState state) {
        validate(agentId, agentVersion, state);
        return new AgentRunSnapshot(agentId, agentVersion, state);
    }

    /**
     * @return Snapshot 持有的不可变运行状态
     */
    public AgentRunState getState() { return state; }

    public String getAgentId() { return agentId; }
    public String getAgentVersion() { return agentVersion; }

    private static void validate(String agentId, String agentVersion, AgentRunState state) {
        if (!StringUtil.hasText(agentId)) {
            throw new IllegalStateException("agentId must not be blank");
        }
        if (!StringUtil.hasText(agentVersion)) {
            throw new IllegalStateException("agentVersion must not be blank");
        }
        if (state == null || state.getRunId() == null) {
            throw new IllegalStateException("state and runId must not be null");
        }
        if (state.getExecutionPolicy() == null) {
            throw new IllegalStateException("executionPolicy must not be null");
        }
        if (state.getStatus() == null) {
            throw new IllegalStateException("status must not be null");
        }
        if (state.getPhase() == null) {
            throw new IllegalStateException("phase must not be null");
        }
    }

}

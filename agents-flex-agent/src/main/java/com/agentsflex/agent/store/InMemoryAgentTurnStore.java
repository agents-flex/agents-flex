/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.store;

import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.exception.AgentConversationBusyException;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 AgentTurnStore，主要用于默认运行环境、单元测试和本地开发。
 *
 * <p>该实现通过 turnId 粒度的同步块实现版本检查和写入原子性。生产环境的长任务应替换为数据库、
 * Redis 等持久化实现，并在存储层使用 CAS、事务或条件更新保证相同语义。</p>
 */
public final class InMemoryAgentTurnStore implements AgentTurnStore {

    /**
     * 按 turnId 保存的最新不可变快照。
     */
    private final ConcurrentMap<String, AgentTurnSnapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * 返回最新快照副本；不存在时返回 {@code null}。
     */
    @Override
    public AgentTurnSnapshot load(String turnId) {
        AgentTurnSnapshot snapshot = snapshots.get(turnId);
        return snapshot == null ? null : snapshot.copy();
    }

    /**
     * 在线性内存快照中查找指定会话唯一的非终态 Turn。
     *
     * @return 防御性快照副本；没有活动 Turn 时返回 {@code null}
     */
    @Override
    public AgentTurnSnapshot findActiveTurn(String conversationId) {
        if (conversationId == null) return null;
        synchronized (snapshots) {
            for (AgentTurnSnapshot snapshot : snapshots.values()) {
                AgentTurnState state = snapshot.getState();
                Object value = state.getMetadata().get("agentsflex.conversationId");
                if (conversationId.equals(value) && !state.getStatus().isTerminal()) {
                    return snapshot.copy();
                }
            }
            return null;
        }
    }

    /**
     * @return 进程当前毫秒时间，用于重试和超时判断
     */
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 按 expectedVersion 执行 CAS 保存并返回版本加一的新快照。
     */
    @Override
    public AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        String turnId = snapshot.getState().getTurnId();
        synchronized (snapshots) {
            AgentTurnSnapshot current = snapshots.get(turnId);
            if (expectedVersion == -1) {
                Object conversationId = snapshot.getState().getMetadata()
                    .get("agentsflex.conversationId");
                if (conversationId != null) {
                    AgentTurnSnapshot active = findActiveTurn(String.valueOf(conversationId));
                    if (active != null) {
                        throw new AgentConversationBusyException(String.valueOf(conversationId),
                            active.getState().getTurnId(), active.getState().getStatus());
                    }
                }
            }
            long actualVersion = current == null ? -1 : current.getState().getVersion();
            if (actualVersion != expectedVersion) {
                throw new AgentTurnVersionConflictException(turnId, expectedVersion, actualVersion);
            }
            AgentTurnSnapshot candidate = current != null
                && current.getState().isCancellationRequested()
                ? snapshot.withState(snapshot.getState().toBuilder()
                .cancellationRequested(true).build())
                : snapshot;
            AgentTurnSnapshot saved = candidate.withVersion(expectedVersion + 1);
            snapshots.put(turnId, saved.copy());
            return saved.copy();
        }
    }

    /**
     * 单调写入取消标记，不覆盖其他执行状态。
     */
    @Override
    public boolean requestCancellation(String turnId) {
        if (turnId == null) {
            throw new IllegalArgumentException("turnId must not be null");
        }
        synchronized (snapshots) {
            AgentTurnSnapshot current = snapshots.get(turnId);
            if (current == null) {
                throw new IllegalStateException("AgentTurn snapshot not found: " + turnId);
            }
            if (current.getState().getStatus().isTerminal()
                || current.getState().isCancellationRequested()) {
                return false;
            }
            // 取消是独立于状态版本的单调控制信号，普通 Snapshot 保存时会合并并保留该标记。
            AgentTurnSnapshot cancelled = current.withState(current.getState().toBuilder()
                .cancellationRequested(true)
                .build());
            snapshots.put(turnId, cancelled.copy());
            return true;
        }
    }

}

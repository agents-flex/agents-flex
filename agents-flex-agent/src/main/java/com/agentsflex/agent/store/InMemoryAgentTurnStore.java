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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 AgentTurnStore，主要用于默认运行环境、单元测试和本地开发。
 *
 * <p>该实现通过 turnId 粒度的同步块实现版本检查和写入原子性。生产环境的长任务应替换为数据库、
 * Redis 等持久化实现，并在存储层使用 CAS、事务或条件更新保证相同语义。</p>
 */
public final class InMemoryAgentTurnStore implements AgentTurnStore {

    /** 按 turnId 保存的最新不可变快照。 */
    private final ConcurrentMap<String, AgentTurnSnapshot> snapshots = new ConcurrentHashMap<>();

    /** 返回最新快照副本；不存在时返回 {@code null}。 */
    @Override
    public AgentTurnSnapshot load(String turnId) {
        AgentTurnSnapshot snapshot = snapshots.get(turnId);
        return snapshot == null ? null : snapshot.copy();
    }

    /** 按 expectedVersion 执行 CAS 保存并返回版本加一的新快照。 */
    @Override
    public AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        String turnId = snapshot.getState().getTurnId();
        synchronized (snapshots) {
            AgentTurnSnapshot current = snapshots.get(turnId);
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

    /** 单调写入取消标记，不覆盖其他执行状态。 */
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

    /** 查询当前最新快照是否已经收到取消请求。 */
    @Override
    public boolean isCancellationRequested(String turnId) {
        AgentTurnSnapshot snapshot = snapshots.get(turnId);
        return snapshot != null && snapshot.getState().isCancellationRequested();
    }

    /** 在同一个同步临界区保存等待中的父 Turn 和新建子 Turn。 */
    @Override
    public ParentChildTurnSnapshots saveParentAndChild(AgentTurnSnapshot parent,
                                                       long expectedParentVersion,
                                                       AgentTurnSnapshot child) {
        if (parent == null || child == null) {
            throw new IllegalArgumentException("parent and child snapshots must not be null");
        }
        synchronized (snapshots) {
            AgentTurnSnapshot currentParent = snapshots.get(parent.getState().getTurnId());
            long actualParentVersion = currentParent == null
                ? -1 : currentParent.getState().getVersion();
            if (actualParentVersion != expectedParentVersion) {
                throw new AgentTurnVersionConflictException(parent.getState().getTurnId(),
                    expectedParentVersion, actualParentVersion);
            }
            if (snapshots.containsKey(child.getState().getTurnId())) {
                throw new AgentTurnVersionConflictException(child.getState().getTurnId(), -1,
                    snapshots.get(child.getState().getTurnId()).getState().getVersion());
            }
            AgentTurnSnapshot savedParent = parent.withVersion(expectedParentVersion + 1);
            AgentTurnSnapshot savedChild = child.withVersion(0);
            snapshots.put(savedParent.getState().getTurnId(), savedParent.copy());
            snapshots.put(savedChild.getState().getTurnId(), savedChild.copy());
            return new ParentChildTurnSnapshots(savedParent.copy(), savedChild.copy());
        }
    }

    /** 原子领取可运行且没有有效租约的 Turn，并为每次领取生成唯一 leaseId。 */
    @Override
    public List<AgentTurnSnapshot> claimRunnable(String workerId, long now,
                                                 long leaseMillis, int limit) {
        if (workerId == null || leaseMillis <= 0 || limit <= 0) {
            throw new IllegalArgumentException("invalid lease request");
        }
        List<AgentTurnSnapshot> claimed = new ArrayList<>();
        synchronized (snapshots) {
            for (AgentTurnSnapshot current : snapshots.values()) {
                if (claimed.size() >= limit) {
                    break;
                }
                if (!isRunnable(current, now)
                    || hasLeasedParent(current, now)
                    || (current.getState().getLeaseUntil() > now
                        && current.getState().getLeaseOwner() != null)) {
                    continue;
                }
                AgentTurnState state = current.getState();
                AgentTurnSnapshot leased = current.withState(state.toBuilder()
                    .leaseOwner(workerId)
                    .leaseId(UUID.randomUUID().toString())
                    .leaseUntil(now + leaseMillis)
                    .version(state.getVersion() + 1)
                    .build());
                snapshots.put(state.getTurnId(), leased.copy());
                claimed.add(leased.copy());
            }
        }
        return claimed;
    }

    /** 仅允许当前 leaseId 持有者延长尚未过期的租约。 */
    @Override
    public AgentTurnSnapshot renewLease(String turnId, String workerId, String leaseId,
                                       long now, long leaseUntil) {
        synchronized (snapshots) {
            AgentTurnSnapshot current = requireOwned(turnId, workerId, leaseId);
            if (current.getState().getLeaseUntil() <= now || leaseUntil <= now) {
                throw new IllegalStateException("AgentTurn lease has expired: " + turnId);
            }
            AgentTurnSnapshot renewed = current.withState(current.getState().toBuilder()
                .leaseUntil(leaseUntil)
                .build());
            snapshots.put(turnId, renewed.copy());
            return renewed.copy();
        }
    }

    /** 释放匹配 Worker 和 leaseId 的租约；过期调用不会影响新租约。 */
    @Override
    public void releaseLease(String turnId, String workerId, String leaseId) {
        synchronized (snapshots) {
            AgentTurnSnapshot current = snapshots.get(turnId);
            if (current == null || !workerId.equals(current.getState().getLeaseOwner())
                || !leaseId.equals(current.getState().getLeaseId())) {
                return;
            }
            AgentTurnSnapshot released = current.withState(current.getState().toBuilder()
                .leaseOwner(null)
                .leaseId(null)
                .leaseUntil(0)
                .build());
            snapshots.put(turnId, released.copy());
        }
    }

    /** 查找已经终止但父 Turn 仍等待其完成信号的子 Turn。 */
    @Override
    public List<AgentTurnSnapshot> findTerminalChildrenWithWaitingParent(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be greater than 0");
        List<AgentTurnSnapshot> result = new ArrayList<>();
        synchronized (snapshots) {
            for (AgentTurnSnapshot child : snapshots.values()) {
                if (result.size() >= limit) break;
                AgentTurnState childState = child.getState();
                if (!childState.getStatus().isTerminal()
                    || childState.getParentTurnId() == null) continue;
                AgentTurnSnapshot parent = snapshots.get(childState.getParentTurnId());
                AgentTurnState parentState = parent == null ? null : parent.getState();
                if (parentState != null
                    && parentState.getStatus() == AgentTurnStatus.WAITING_FOR_CHILD
                    && parentState.getSuspension() != null
                    && childState.getTurnId().equals(
                        parentState.getSuspension().getCorrelationId())) {
                    result.add(child.copy());
                }
            }
        }
        return result;
    }

    /** 判断快照当前可以由 Worker 领取推进。 */
    private boolean isRunnable(AgentTurnSnapshot snapshot, long now) {
        AgentTurnState state = snapshot.getState();
        if (state.isCancellationRequested() && !state.getStatus().isTerminal()) {
            return true;
        }
        AgentTurnStatus status = state.getStatus();
        if (status == AgentTurnStatus.READY || status == AgentTurnStatus.RUNNING) {
            return true;
        }
        return status == AgentTurnStatus.RETRY_SCHEDULED && state.getNextRunnableAt() <= now;
    }

    /** 父 Turn 仍由 Worker 推进时，子 Turn 暂不参与领取。 */
    private boolean hasLeasedParent(AgentTurnSnapshot snapshot, long now) {
        if (snapshot.getState().getParentTurnId() == null) {
            return false;
        }
        AgentTurnSnapshot parent = snapshots.get(snapshot.getState().getParentTurnId());
        return parent != null && parent.getState().getLeaseOwner() != null
            && parent.getState().getLeaseUntil() > now;
    }

    /** 校验指定 Worker 和 leaseId 仍拥有目标 Turn。 */
    private AgentTurnSnapshot requireOwned(String turnId, String workerId, String leaseId) {
        AgentTurnSnapshot current = snapshots.get(turnId);
        if (current == null) {
            throw new IllegalStateException("AgentTurn snapshot not found: " + turnId);
        }
        if (!workerId.equals(current.getState().getLeaseOwner()) || leaseId == null
            || !leaseId.equals(current.getState().getLeaseId())) {
            throw new IllegalStateException("AgentTurn lease is not owned by worker: " + workerId);
        }
        return current;
    }
}

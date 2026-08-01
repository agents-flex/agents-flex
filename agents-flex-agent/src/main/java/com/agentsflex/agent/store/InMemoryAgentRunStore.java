/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.store;

import com.agentsflex.agent.AgentRunSnapshot;
import com.agentsflex.agent.AgentRunStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 AgentRunStore，主要用于默认运行模式、单元测试和本地开发。
 *
 * <p>该实现通过 runId 粒度的同步块实现版本检查和写入原子性。生产环境的长任务应替换为数据库、
 * Redis 等持久化实现，并在存储层使用 CAS、事务或条件更新保证相同语义。</p>
 */
public final class InMemoryAgentRunStore implements AgentRunStore {

    /** 按 runId 保存的最新不可变快照。 */
    private final ConcurrentMap<String, AgentRunSnapshot> snapshots = new ConcurrentHashMap<>();

    /** 返回最新快照副本；不存在时返回 {@code null}。 */
    @Override
    public AgentRunSnapshot load(String runId) {
        AgentRunSnapshot snapshot = snapshots.get(runId);
        return snapshot == null ? null : snapshot.copy();
    }

    /** 按 expectedVersion 执行 CAS 保存并返回版本加一的新快照。 */
    @Override
    public AgentRunSnapshot save(AgentRunSnapshot snapshot, long expectedVersion) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        String runId = snapshot.getRunId();
        synchronized (snapshots) {
            AgentRunSnapshot current = snapshots.get(runId);
            long actualVersion = current == null ? -1 : current.getVersion();
            if (actualVersion != expectedVersion) {
                throw new AgentRunVersionConflictException(runId, expectedVersion, actualVersion);
            }
            AgentRunSnapshot candidate = current != null && current.isCancellationRequested()
                ? snapshot.toBuilder().cancellationRequested(true).build()
                : snapshot;
            AgentRunSnapshot saved = candidate.withVersion(expectedVersion + 1);
            snapshots.put(runId, saved.copy());
            return saved.copy();
        }
    }

    /** 单调写入取消标记，不覆盖其他执行状态。 */
    @Override
    public boolean requestCancellation(String runId) {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        synchronized (snapshots) {
            AgentRunSnapshot current = snapshots.get(runId);
            if (current == null) {
                throw new IllegalStateException("AgentRun checkpoint not found: " + runId);
            }
            if (current.getStatus().isTerminal() || current.isCancellationRequested()) {
                return false;
            }
            // 取消是独立于状态版本的单调控制信号，普通 Checkpoint 保存时会合并并保留该标记。
            AgentRunSnapshot cancelled = current.toBuilder()
                .cancellationRequested(true)
                .build();
            snapshots.put(runId, cancelled.copy());
            return true;
        }
    }

    /** 查询当前最新快照是否已经收到取消请求。 */
    @Override
    public boolean isCancellationRequested(String runId) {
        AgentRunSnapshot snapshot = snapshots.get(runId);
        return snapshot != null && snapshot.isCancellationRequested();
    }

    /** 在同一个同步临界区保存等待中的父 Run 和新建子 Run。 */
    @Override
    public ParentChildRunSnapshots saveParentAndChild(AgentRunSnapshot parent,
                                                       long expectedParentVersion,
                                                       AgentRunSnapshot child) {
        if (parent == null || child == null) {
            throw new IllegalArgumentException("parent and child snapshots must not be null");
        }
        synchronized (snapshots) {
            AgentRunSnapshot currentParent = snapshots.get(parent.getRunId());
            long actualParentVersion = currentParent == null ? -1 : currentParent.getVersion();
            if (actualParentVersion != expectedParentVersion) {
                throw new AgentRunVersionConflictException(parent.getRunId(),
                    expectedParentVersion, actualParentVersion);
            }
            if (snapshots.containsKey(child.getRunId())) {
                throw new AgentRunVersionConflictException(child.getRunId(), -1,
                    snapshots.get(child.getRunId()).getVersion());
            }
            AgentRunSnapshot savedParent = parent.withVersion(expectedParentVersion + 1);
            AgentRunSnapshot savedChild = child.withVersion(0);
            snapshots.put(savedParent.getRunId(), savedParent.copy());
            snapshots.put(savedChild.getRunId(), savedChild.copy());
            return new ParentChildRunSnapshots(savedParent.copy(), savedChild.copy());
        }
    }

    /** 原子领取可运行且没有有效租约的 Run，并为每次领取生成唯一 leaseId。 */
    @Override
    public List<AgentRunSnapshot> claimRunnable(String workerId, long now,
                                                 long leaseMillis, int limit) {
        if (workerId == null || leaseMillis <= 0 || limit <= 0) {
            throw new IllegalArgumentException("invalid lease request");
        }
        List<AgentRunSnapshot> claimed = new ArrayList<>();
        synchronized (snapshots) {
            for (AgentRunSnapshot current : snapshots.values()) {
                if (claimed.size() >= limit) {
                    break;
                }
                if (!isRunnable(current, now)
                    || hasLeasedParent(current, now)
                    || (current.getLeaseUntil() > now && current.getLeaseOwner() != null)) {
                    continue;
                }
                AgentRunSnapshot leased = current.toBuilder()
                    .leaseOwner(workerId)
                    .leaseId(UUID.randomUUID().toString())
                    .leaseUntil(now + leaseMillis)
                    .version(current.getVersion() + 1)
                    .build();
                snapshots.put(current.getRunId(), leased.copy());
                claimed.add(leased.copy());
            }
        }
        return claimed;
    }

    /** 仅允许当前 leaseId 持有者延长尚未过期的租约。 */
    @Override
    public AgentRunSnapshot renewLease(String runId, String workerId, String leaseId,
                                       long now, long leaseUntil) {
        synchronized (snapshots) {
            AgentRunSnapshot current = requireOwned(runId, workerId, leaseId);
            if (current.getLeaseUntil() <= now || leaseUntil <= now) {
                throw new IllegalStateException("AgentRun lease has expired: " + runId);
            }
            AgentRunSnapshot renewed = current.toBuilder()
                .leaseUntil(leaseUntil)
                .build();
            snapshots.put(runId, renewed.copy());
            return renewed.copy();
        }
    }

    /** 释放匹配 Worker 和 leaseId 的租约；过期调用不会影响新租约。 */
    @Override
    public void releaseLease(String runId, String workerId, String leaseId) {
        synchronized (snapshots) {
            AgentRunSnapshot current = snapshots.get(runId);
            if (current == null || !workerId.equals(current.getLeaseOwner())
                || !leaseId.equals(current.getLeaseId())) {
                return;
            }
            AgentRunSnapshot released = current.toBuilder()
                .leaseOwner(null)
                .leaseId(null)
                .leaseUntil(0)
                .build();
            snapshots.put(runId, released.copy());
        }
    }

    /** 查找已经终止但父 Run 仍等待其完成信号的子 Run。 */
    @Override
    public List<AgentRunSnapshot> findTerminalChildrenWithWaitingParent(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be greater than 0");
        List<AgentRunSnapshot> result = new ArrayList<>();
        synchronized (snapshots) {
            for (AgentRunSnapshot child : snapshots.values()) {
                if (result.size() >= limit) break;
                if (!child.getStatus().isTerminal() || child.getParentRunId() == null) continue;
                AgentRunSnapshot parent = snapshots.get(child.getParentRunId());
                if (parent != null && parent.getStatus() == AgentRunStatus.WAITING_FOR_CHILD
                    && parent.getSuspension() != null
                    && child.getRunId().equals(parent.getSuspension().getCorrelationId())) {
                    result.add(child.copy());
                }
            }
        }
        return result;
    }

    /** 判断快照当前可以由 Worker 领取推进。 */
    private boolean isRunnable(AgentRunSnapshot snapshot, long now) {
        if (snapshot.isCancellationRequested() && !snapshot.getStatus().isTerminal()) {
            return true;
        }
        AgentRunStatus status = snapshot.getStatus();
        if (status == AgentRunStatus.READY || status == AgentRunStatus.RUNNING) {
            return true;
        }
        return status == AgentRunStatus.RETRY_SCHEDULED && snapshot.getNextRunAt() <= now;
    }

    /** 父 Run 仍由 Worker 推进时，子 Run 暂不参与领取。 */
    private boolean hasLeasedParent(AgentRunSnapshot snapshot, long now) {
        if (snapshot.getParentRunId() == null) {
            return false;
        }
        AgentRunSnapshot parent = snapshots.get(snapshot.getParentRunId());
        return parent != null && parent.getLeaseOwner() != null && parent.getLeaseUntil() > now;
    }

    /** 校验指定 Worker 和 leaseId 仍拥有目标 Run。 */
    private AgentRunSnapshot requireOwned(String runId, String workerId, String leaseId) {
        AgentRunSnapshot current = snapshots.get(runId);
        if (current == null) {
            throw new IllegalStateException("AgentRun checkpoint not found: " + runId);
        }
        if (!workerId.equals(current.getLeaseOwner()) || leaseId == null
            || !leaseId.equals(current.getLeaseId())) {
            throw new IllegalStateException("AgentRun lease is not owned by worker: " + workerId);
        }
        return current;
    }
}

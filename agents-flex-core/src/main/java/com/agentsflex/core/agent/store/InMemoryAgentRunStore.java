/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.store;

import com.agentsflex.core.agent.AgentRunSnapshot;
import com.agentsflex.core.agent.AgentRunStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内 AgentRunStore，主要用于默认运行模式、单元测试和本地开发。
 *
 * <p>该实现通过 runId 粒度的同步块实现版本检查和写入原子性。生产环境的长任务应替换为数据库、
 * Redis 等持久化实现，并在存储层使用 CAS、事务或条件更新保证相同语义。</p>
 */
public final class InMemoryAgentRunStore implements AgentRunStore {

    private final ConcurrentMap<String, AgentRunSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public AgentRunSnapshot load(String runId) {
        AgentRunSnapshot snapshot = snapshots.get(runId);
        return snapshot == null ? null : snapshot.copy();
    }

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

    @Override
    public boolean isCancellationRequested(String runId) {
        AgentRunSnapshot snapshot = snapshots.get(runId);
        return snapshot != null && snapshot.isCancellationRequested();
    }

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
                    .leaseUntil(now + leaseMillis)
                    .version(current.getVersion() + 1)
                    .build();
                snapshots.put(current.getRunId(), leased.copy());
                claimed.add(leased.copy());
            }
        }
        return claimed;
    }

    @Override
    public AgentRunSnapshot renewLease(String runId, String workerId, long leaseUntil) {
        synchronized (snapshots) {
            AgentRunSnapshot current = requireOwned(runId, workerId);
            AgentRunSnapshot renewed = current.toBuilder()
                .leaseUntil(leaseUntil)
                .version(current.getVersion() + 1)
                .build();
            snapshots.put(runId, renewed.copy());
            return renewed.copy();
        }
    }

    @Override
    public void releaseLease(String runId, String workerId) {
        synchronized (snapshots) {
            AgentRunSnapshot current = snapshots.get(runId);
            if (current == null || !workerId.equals(current.getLeaseOwner())) {
                return;
            }
            AgentRunSnapshot released = current.toBuilder()
                .leaseOwner(null)
                .leaseUntil(0)
                .version(current.getVersion() + 1)
                .build();
            snapshots.put(runId, released.copy());
        }
    }

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

    private AgentRunSnapshot requireOwned(String runId, String workerId) {
        AgentRunSnapshot current = snapshots.get(runId);
        if (current == null) {
            throw new IllegalStateException("AgentRun checkpoint not found: " + runId);
        }
        if (!workerId.equals(current.getLeaseOwner())) {
            throw new IllegalStateException("AgentRun lease is not owned by worker: " + workerId);
        }
        return current;
    }
}

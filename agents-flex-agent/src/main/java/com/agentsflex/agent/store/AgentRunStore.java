/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.store;

import com.agentsflex.agent.AgentRunSnapshot;
import com.agentsflex.agent.loader.AgentLoader;

import java.util.Collections;
import java.util.List;

/**
 * AgentRun Checkpoint 存储接口。
 *
 * <p>Store 只保存可序列化的 {@link AgentRunSnapshot}，不保存 ChatModel、Tool 或 Agent 等运行时对象。
 * 恢复时由 {@link AgentLoader} 根据 agentId 重新绑定这些对象。</p>
 *
 * <p>{@link #save(AgentRunSnapshot, long)} 使用乐观锁版本号，避免多个 Worker 或线程静默覆盖同一个
 * AgentRun 的最新状态。新建记录时 expectedVersion 应为 {@code -1}。</p>
 */
public interface AgentRunStore {

    /**
     * 返回调度存储使用的当前时间。
     *
     * <p>分布式 Store 应覆盖该方法并使用数据库或 Redis 服务端时间，避免应用节点时钟漂移
     * 导致 Lease 被提前抢占。进程内实现默认使用本机时间。</p>
     */
    default long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 加载指定运行的最新 Checkpoint。
     *
     * @return Snapshot；不存在时返回 {@code null}
     */
    AgentRunSnapshot load(String runId);

    /**
     * 原子保存 Checkpoint 并生成下一个版本号。
     *
     * @param snapshot 要保存的状态快照
     * @param expectedVersion 调用方认为 Store 当前持有的版本；首次保存为 -1
     * @return 已写入 Store、包含新版本号的 Snapshot
     * @throws AgentRunVersionConflictException Store 版本与 expectedVersion 不一致时抛出
     */
    AgentRunSnapshot save(AgentRunSnapshot snapshot, long expectedVersion);

    /**
     * 原子记录取消请求。
     *
     * <p>取消标记是单调信号：一旦写入，在 Run 进入终止状态前不能被后续 Checkpoint 清除。
     * 该操作不要求调用方持有 Worker Lease，因此 HTTP 控制面可以取消正在后台执行或等待中的任务。</p>
     *
     * @return 本次调用是否首次写入取消请求；Run 已终止或已经请求取消时返回 {@code false}
     */
    boolean requestCancellation(String runId);

    /** 查询指定 Run 是否已经收到持久化取消请求。 */
    boolean isCancellationRequested(String runId);

    /**
     * 原子保存进入等待状态的父 Run，并创建对应的子 Run。
     *
     * <p>外部 Store 应在同一事务中完成父记录条件更新和子记录插入，避免只提交其中一个状态。</p>
     */
    default ParentChildRunSnapshots saveParentAndChild(AgentRunSnapshot parent,
                                                        long expectedParentVersion,
                                                        AgentRunSnapshot child) {
        throw new UnsupportedOperationException("atomic child creation is not supported");
    }

    /**
     * 原子领取当前可执行且没有有效租约的 Run。
     *
     * <p>Store 实现应在领取时写入 leaseOwner、leaseUntil 并增加版本号。默认实现表示不支持调度。</p>
     */
    default List<AgentRunSnapshot> claimRunnable(String workerId, long now,
                                                  long leaseMillis, int limit) {
        return Collections.emptyList();
    }

    /**
     * 延长指定 Worker 持有的有效租约。
     *
     * <p>续租只更新租约时间，不改变 Checkpoint 版本。leaseId 必须与领取时返回的唯一令牌一致，
     * 防止同名 Worker 或已经失效的进程续租新的租约。</p>
     */
    default AgentRunSnapshot renewLease(String runId, String workerId, String leaseId,
                                        long now, long leaseUntil) {
        throw new UnsupportedOperationException("lease renewal is not supported");
    }

    /** 仅在 Worker ID 和唯一租约令牌都匹配时释放租约。 */
    default void releaseLease(String runId, String workerId, String leaseId) {
    }

    /**
     * 查询已经终止、但父 Run 仍处于等待状态的子 Run，供 Worker 修复父任务唤醒。
     */
    default List<AgentRunSnapshot> findTerminalChildrenWithWaitingParent(int limit) {
        return Collections.emptyList();
    }
}

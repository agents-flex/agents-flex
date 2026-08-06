/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.store;

import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentConversationBusyException;
import com.agentsflex.agent.loader.AgentLoader;

import java.util.Collections;
import java.util.List;

/**
 * AgentTurn Snapshot 存储接口。
 *
 * <p>Store 只保存可序列化的 {@link AgentTurnSnapshot}，不保存 ChatModel、Tool 或 Agent 等运行时对象。
 * 恢复时由 {@link AgentLoader} 根据 agentId 重新绑定这些对象。</p>
 *
 * <p>{@link #save(AgentTurnSnapshot, long)} 使用乐观锁版本号，避免多个 Worker 或线程静默覆盖同一个
 * AgentTurn 的最新状态。新建记录时 expectedVersion 应为 {@code -1}。</p>
 */
public interface AgentTurnStore {

    /** 查找指定业务会话当前未结束的 Turn。持久化实现应按 conversationId 建立索引。 */
    default AgentTurnSnapshot findActiveTurn(String conversationId) {
        return null;
    }

    /** 原子创建绑定业务会话的新 Turn；冲突时应抛出 {@link AgentConversationBusyException}。 */
    default AgentTurnSnapshot saveNewConversationTurn(AgentTurnSnapshot snapshot) {
        return save(snapshot, -1);
    }

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
     * 加载指定运行的最新 Snapshot。
     *
     * @return Snapshot；不存在时返回 {@code null}
     */
    AgentTurnSnapshot load(String turnId);

    /**
     * 原子保存 Snapshot 并生成下一个版本号。
     *
     * @param snapshot        要保存的状态快照
     * @param expectedVersion 调用方认为 Store 当前持有的版本；首次保存为 -1
     * @return 已写入 Store、包含新版本号的 Snapshot
     * @throws AgentTurnVersionConflictException Store 版本与 expectedVersion 不一致时抛出
     */
    AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion);

    /**
     * 原子记录取消请求。
     *
     * <p>取消标记是单调信号：一旦写入，在 Turn 进入终止状态前不能被后续 Snapshot 清除。
     * 该操作不要求调用方持有 Worker Lease，因此 HTTP 控制面可以取消正在后台执行或等待中的任务。</p>
     *
     * @return 本次调用是否首次写入取消请求；Turn 已终止或已经请求取消时返回 {@code false}
     */
    boolean requestCancellation(String turnId);

    /**
     * 查询指定 Turn 是否已经收到持久化取消请求。
     */
    boolean isCancellationRequested(String turnId);

    /**
     * 原子保存进入等待状态的父 Turn，并创建对应的子 Turn。
     *
     * <p>外部 Store 应在同一事务中完成父记录条件更新和子记录插入，避免只提交其中一个状态。</p>
     */
    default ParentChildTurnSnapshots saveParentAndChild(AgentTurnSnapshot parent,
                                                        long expectedParentVersion,
                                                        AgentTurnSnapshot child) {
        throw new UnsupportedOperationException("atomic child creation is not supported");
    }

    /**
     * 原子领取当前可执行且没有有效租约的 Turn。
     *
     * <p>Store 实现应在领取时写入 leaseOwner、leaseUntil 并增加版本号。默认实现表示不支持调度。</p>
     */
    default List<AgentTurnSnapshot> claimRunnable(String workerId, long now,
                                                  long leaseMillis, int limit) {
        return Collections.emptyList();
    }

    /**
     * 延长指定 Worker 持有的有效租约。
     *
     * <p>续租只更新租约时间，不改变 Snapshot 版本。leaseId 必须与领取时返回的唯一令牌一致，
     * 防止同名 Worker 或已经失效的进程续租新的租约。</p>
     */
    default AgentTurnSnapshot renewLease(String turnId, String workerId, String leaseId,
                                         long now, long leaseUntil) {
        throw new UnsupportedOperationException("lease renewal is not supported");
    }

    /**
     * 仅在 Worker ID 和唯一租约令牌都匹配时释放租约。
     */
    default void releaseLease(String turnId, String workerId, String leaseId) {
    }

    /**
     * 查询已经终止、但父 Turn 仍处于等待状态的子 Turn，供 Worker 修复父任务唤醒。
     */
    default List<AgentTurnSnapshot> findTerminalChildrenWithWaitingParent(int limit) {
        return Collections.emptyList();
    }
}

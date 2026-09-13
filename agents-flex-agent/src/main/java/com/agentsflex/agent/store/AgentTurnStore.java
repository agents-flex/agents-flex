/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.store;

import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;
import com.agentsflex.agent.loader.AgentLoader;

import java.util.List;

/**
 * AgentTurn Snapshot 存储接口。
 *
 * <p>Store 只保存可序列化的 {@link AgentTurnSnapshot}，不保存 ChatModel、Tool 或 Agent 等运行时对象。
 * 恢复时由 {@link AgentLoader} 根据 agentId 重新绑定这些对象。</p>
 *
 * <p>{@link #save(AgentTurnSnapshot, long)} 使用乐观锁版本号，避免多个线程静默覆盖同一个 AgentTurn
 * 的最新状态。新建记录时 expectedVersion 应为 {@code -1}。</p>
 */
public interface AgentTurnStore {

    /**
     * 查找指定业务会话当前未结束的 Turn。持久化实现应按 conversationId 建立索引。
     */
    AgentTurnSnapshot findActiveTurn(String conversationId);

    /**
     * 返回调度存储使用的当前时间。
     *
     * <p>分布式 Store 可使用数据库或 Redis 服务端时间，避免应用节点时钟漂移影响重试和超时判断。
     * 进程内实现可以使用本机时间。</p>
     */
    long currentTimeMillis();

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
     * 该操作不要求调用方持有执行锁，因此 HTTP 控制面可以取消正在执行或等待中的任务。</p>
     *
     * @return 本次调用是否首次写入取消请求；Turn 已终止或已经请求取消时返回 {@code false}
     */
    boolean requestCancellation(String turnId);

}

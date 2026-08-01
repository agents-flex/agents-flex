/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.store.AgentRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 AgentRunStore 领取可执行任务并推进到终止或阻塞状态的 Worker。
 *
 * <p>Worker 通过 Store 租约确保同一 Run 同时只由一个进程推进。执行期间后台心跳按租约时长的
 * 三分之一续租；租约丢失后 Runner 会在下一个 Checkpoint 边界拒绝继续写入。</p>
 *
 * <p>Worker 可以由外部调度器调用 {@link #pollAndRun(int)}，也可以使用
 * {@link #startPolling(long, int)} 启动进程内定时轮询。关闭 Worker 不保证强制中断正在执行的
 * 模型 HTTP 请求或业务工具。</p>
 */
public final class AgentWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);

    /** 在共享 Store 中标识当前 Worker 进程的稳定名称。 */
    private final String workerId;
    /** 负责恢复和推进 Run 的运行器。 */
    private final AgentRunner runner;
    /** 每次领取和续租使用的租约时长。 */
    private final long leaseMillis;
    /** 从持久化快照重建非持久化调用上下文的提供者。 */
    private final AgentInvocationContextProvider invocationContextProvider;
    /** 独立执行租约心跳的单线程调度器。 */
    private final ScheduledExecutorService leaseScheduler;
    /** 可选的自动轮询调度器。 */
    private ScheduledExecutorService scheduler;
    /** Worker 是否已经关闭并拒绝新的轮询。 */
    private boolean closed;
    /** 当前尚未返回的同步轮询调用数量。 */
    private int activePolls;

    /** 创建使用空调用上下文的 Worker。 */
    public AgentWorker(String workerId, AgentRunner runner, long leaseMillis) {
        this(workerId, runner, leaseMillis, AgentInvocationContextProvider.empty());
    }

    /** 创建在恢复 Run 时可以重新附加租户服务和请求身份的 Worker。 */
    public AgentWorker(String workerId, AgentRunner runner, long leaseMillis,
                       AgentInvocationContextProvider invocationContextProvider) {
        if (workerId == null || runner == null || leaseMillis <= 0) {
            throw new IllegalArgumentException("workerId, runner and leaseMillis are required");
        }
        if (invocationContextProvider == null) {
            throw new IllegalArgumentException("invocationContextProvider must not be null");
        }
        this.workerId = workerId;
        this.runner = runner;
        this.leaseMillis = leaseMillis;
        this.invocationContextProvider = invocationContextProvider;
        this.leaseScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-lease-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 从 Store 领取并同步执行一批任务。
     *
     * @param limit 本次最多领取的任务数
     * @return 已推进到终止或阻塞状态的运行列表
     */
    public List<AgentRun> pollAndRun(int limit) {
        beginPoll();
        try {
            return doPollAndRun(limit);
        } finally {
            endPoll();
        }
    }

    private List<AgentRun> doPollAndRun(int limit) {
        // 先修复可能因进程退出而遗漏的父 Run 唤醒，再消费外部恢复命令。
        runner.recoverCompletedChildren(limit);
        runner.processCommands(workerId, leaseMillis, limit);
        List<AgentRun> results = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            List<AgentRunSnapshot> claimed = runner.getRunStore().claimRunnable(
                workerId, runner.getRunStore().currentTimeMillis(), leaseMillis, 1);
            if (claimed.isEmpty()) break;
            AgentRunSnapshot snapshot = claimed.get(0);
            AgentRun run = null;
            ScheduledFuture<?> heartbeat = null;
            try {
                run = runner.restore(snapshot.getRunId(),
                    invocationContextProvider.provide(snapshot));
                run.updateLease(workerId, snapshot.getLeaseId(), snapshot.getLeaseUntil());
                heartbeat = startLeaseHeartbeat(run);
                run = runner.runLeased(run, workerId, snapshot.getLeaseId());
                runner.resumeParentFromChild(run);
            } finally {
                if (heartbeat != null) heartbeat.cancel(false);
                if (run != null) {
                    synchronized (run) {
                        runner.getRunStore().releaseLease(snapshot.getRunId(), workerId,
                            snapshot.getLeaseId());
                    }
                } else {
                    runner.getRunStore().releaseLease(snapshot.getRunId(), workerId,
                        snapshot.getLeaseId());
                }
            }
            if (run != null) {
                // 返回释放租约后的最新版本，避免调用方持有过期的乐观锁版本号。
                results.add(runner.restore(run.getId()));
            }
        }
        return results;
    }

    /**
     * 延长指定 Run 的租约并返回新版本快照。
     *
     * <p>调用方若仍持有对应 AgentRun，应恢复该快照或使用 {@link #renewLease(AgentRun)} 同步本地版本。</p>
     */
    public AgentRunSnapshot renewLease(String runId, String leaseId) {
        long now = runner.getRunStore().currentTimeMillis();
        return runner.getRunStore().renewLease(runId, workerId, leaseId,
            now, now + leaseMillis);
    }

    /** 延长租约并同步更新正在执行的 AgentRun 版本。 */
    public AgentRunSnapshot renewLease(AgentRun run) {
        synchronized (run) {
            AgentRunSnapshot snapshot = renewLease(run.getId(), run.getLeaseId());
            run.updateLease(snapshot.getLeaseOwner(), snapshot.getLeaseId(),
                snapshot.getLeaseUntil());
            return snapshot;
        }
    }

    /** 在 Run 执行期间按租约时长的三分之一周期自动续租。 */
    private ScheduledFuture<?> startLeaseHeartbeat(AgentRun run) {
        long interval = Math.max(1, leaseMillis / 3);
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        return leaseScheduler.scheduleWithFixedDelay(() -> {
            if (failure.get() != null) return;
            try {
                renewLease(run);
            } catch (RuntimeException error) {
                failure.compareAndSet(null, error);
                synchronized (run) {
                    run.updateLease(run.getLeaseOwner(), run.getLeaseId(), 0);
                }
                log.warn("Agent worker lost lease, workerId={}, runId={}",
                    workerId, run.getId(), error);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** 按固定间隔自动领取并执行任务。重复调用不会创建多个调度线程。 */
    public synchronized void startPolling(long pollIntervalMillis, int batchSize) {
        if (pollIntervalMillis <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("pollIntervalMillis and batchSize must be greater than 0");
        }
        if (scheduler != null) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("AgentWorker is already closed");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-worker-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                pollAndRun(batchSize);
            } catch (RuntimeException error) {
                log.warn("Agent worker polling failed, workerId={}", workerId, error);
            }
        }, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /** @return 自动轮询线程是否已经启动且尚未关闭 */
    public synchronized boolean isPolling() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /** 停止自动轮询；正在进行的外部调用能否中断由其具体实现决定。 */
    @Override
    public synchronized void close() {
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (activePolls == 0) leaseScheduler.shutdownNow();
    }

    private synchronized void beginPoll() {
        if (closed) throw new IllegalStateException("AgentWorker is already closed");
        activePolls++;
    }

    private synchronized void endPoll() {
        activePolls--;
        if (closed && activePolls == 0) leaseScheduler.shutdownNow();
    }
}

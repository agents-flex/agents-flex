/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 AgentTurnStore 领取可执行任务并推进到终止或阻塞状态的 Worker。
 *
 * <p>Worker 通过 Store 租约确保同一 Turn 同时只由一个进程推进。执行期间后台心跳按租约时长的
 * 三分之一续租；租约丢失后 Runner 会在下一个 Snapshot 边界拒绝继续写入。</p>
 *
 * <p>Worker 可以由外部调度器调用 {@link #pollAndRun(int)}，也可以使用
 * {@link #startPolling(long, int)} 启动进程内定时轮询。关闭 Worker 不保证强制中断正在执行的
 * 模型 HTTP 请求或业务工具。</p>
 */
public final class AgentWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);

    /**
     * 在共享 Store 中标识当前 Worker 进程的稳定名称。
     */
    private final String workerId;
    /**
     * 负责恢复和推进 Turn 的运行器。
     */
    private final AgentRunner runner;
    /**
     * 每次领取和续租使用的租约时长。
     */
    private final long leaseMillis;
    /**
     * 租约续期间隔占租约时长的比例。
     */
    private double leaseRenewalFraction;
    /**
     * 独立执行租约心跳的单线程调度器。
     */
    private final ScheduledExecutorService leaseScheduler;
    /**
     * 同一 Worker 内并发推进不同 Turn 的有界执行器。
     */
    private final ExecutorService turnExecutor;
    private final int maxConcurrentTurns;
    /**
     * 可选的自动轮询调度器。
     */
    private ScheduledExecutorService scheduler;
    /**
     * Worker 是否已经关闭并拒绝新的轮询。
     */
    private boolean closed;
    /**
     * 当前尚未返回的同步轮询调用数量。
     */
    private int activePolls;

    /**
     * 创建从 Snapshot 领取并恢复 Turn 的 Worker。
     */
    public AgentWorker(String workerId, AgentRunner runner, long leaseMillis) {
        this(workerId, runner, leaseMillis, 1);
    }

    private AgentWorker(String workerId, AgentRunner runner, long leaseMillis,
                        int maxConcurrentTurns) {
        if (workerId == null || runner == null || leaseMillis <= 0) {
            throw new IllegalArgumentException("workerId, runner and leaseMillis are required");
        }
        if (maxConcurrentTurns <= 0) {
            throw new IllegalArgumentException("maxConcurrentTurns must be greater than 0");
        }
        this.workerId = workerId;
        this.runner = runner;
        this.leaseMillis = leaseMillis;
        this.leaseRenewalFraction = 1.0 / 3.0;
        this.maxConcurrentTurns = maxConcurrentTurns;
        this.leaseScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-lease-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
        this.turnExecutor = Executors.newFixedThreadPool(maxConcurrentTurns, runnable -> {
            Thread thread = new Thread(runnable, "agent-turn-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 使用轮询配置对象创建 Worker。
     */
    public AgentWorker(AgentRunner runner, AgentWorkerOptions options) {
        this(requireOptions(options).getWorkerId(), runner, options.getLeaseMillis(),
            options.getMaxConcurrentTurns());
        this.options = options;
        this.leaseRenewalFraction = options.getLeaseRenewalFraction();
    }

    private static AgentWorkerOptions requireOptions(AgentWorkerOptions options) {
        if (options == null) throw new IllegalArgumentException("options must not be null");
        return options;
    }

    private AgentWorkerOptions options;

    /**
     * 使用配置对象中的间隔和批量大小启动自动轮询。
     */
    public void startPolling() {
        // 旧构造函数没有 options，因此继续保留原有的 1 秒/单任务默认行为。
        AgentWorkerOptions value = options;
        startPolling(value == null ? 1000 : value.getPollIntervalMillis(),
            value == null ? 1 : value.getBatchSize());
    }

    /**
     * 从 Store 领取并同步执行一批任务。
     *
     * @param limit 本次最多领取的任务数
     * @return 已推进到终止或阻塞状态的运行列表
     */
    public List<AgentTurn> pollAndRun(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        beginPoll();
        try {
            return doPollAndRun(limit);
        } finally {
            endPoll();
        }
    }

    /**
     * 领取可运行快照，逐个恢复并在租约保护下推进到下一个持久化边界。
     *
     * @param limit 本轮最多领取数量
     * @return 本轮实际处理的 Turn
     */
    private List<AgentTurn> doPollAndRun(int limit) {
        List<AgentTurn> results = new ArrayList<>(limit);
        while (results.size() < limit) {
            int window = Math.min(maxConcurrentTurns, limit - results.size());
            List<AgentTurnSnapshot> claimed = runner.getTurnStore().claimRunnable(
                workerId, runner.getTurnStore().currentTimeMillis(), leaseMillis, window);
            if (claimed.isEmpty()) break;
            List<Future<AgentTurn>> futures = new ArrayList<>(claimed.size());
            for (AgentTurnSnapshot snapshot : claimed) {
                futures.add(turnExecutor.submit(() -> runClaimed(snapshot)));
            }
            for (Future<AgentTurn> future : futures) {
                try {
                    AgentTurn turn = future.get();
                    if (turn != null) results.add(turn);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("AgentWorker was interrupted", error);
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    throw new IllegalStateException("AgentWorker turn execution failed", cause);
                }
            }
        }
        return results;
    }

    /**
     * 恢复一个已领取快照、启动心跳、推进 Turn，并确保租约最终释放。
     */
    private AgentTurn runClaimed(AgentTurnSnapshot snapshot) {
        AgentTurn turn = null;
        ScheduledFuture<?> heartbeat = null;
        try {
            turn = runner.restore(snapshot.getState().getTurnId());
            turn.updateLease(workerId, snapshot.getState().getLeaseId(),
                snapshot.getState().getLeaseUntil());
            heartbeat = startLeaseHeartbeat(turn);
            turn = runner.runLeased(turn, workerId, snapshot.getState().getLeaseId());
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
            if (turn != null) {
                synchronized (turn) {
                    runner.getTurnStore().releaseLease(snapshot.getState().getTurnId(), workerId,
                        snapshot.getState().getLeaseId());
                }
            } else {
                runner.getTurnStore().releaseLease(snapshot.getState().getTurnId(), workerId,
                    snapshot.getState().getLeaseId());
            }
        }
        return turn == null ? null : runner.restore(turn.getId());
    }

    /**
     * 延长指定 Turn 的租约并返回新版本快照。
     *
     * <p>调用方若仍持有对应 AgentTurn，应恢复该快照或使用 {@link #renewLease(AgentTurn)} 同步本地版本。</p>
     */
    public AgentTurnSnapshot renewLease(String turnId, String leaseId) {
        long now = runner.getTurnStore().currentTimeMillis();
        return runner.getTurnStore().renewLease(turnId, workerId, leaseId,
            now, now + leaseMillis);
    }

    /**
     * 延长租约并同步更新正在执行的 AgentTurn 版本。
     */
    public AgentTurnSnapshot renewLease(AgentTurn turn) {
        synchronized (turn) {
            AgentTurnSnapshot snapshot = renewLease(turn.getId(), turn.getLeaseId());
            turn.updateLease(snapshot.getState().getLeaseOwner(), snapshot.getState().getLeaseId(),
                snapshot.getState().getLeaseUntil());
            return snapshot;
        }
    }

    /**
     * 在 Turn 执行期间按租约时长的三分之一周期自动续租。
     */
    private ScheduledFuture<?> startLeaseHeartbeat(AgentTurn turn) {
        long interval = Math.max(1, (long) (leaseMillis * leaseRenewalFraction));
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        return leaseScheduler.scheduleWithFixedDelay(() -> {
            if (failure.get() != null) return;
            try {
                renewLease(turn);
            } catch (RuntimeException error) {
                failure.compareAndSet(null, error);
                synchronized (turn) {
                    turn.updateLease(turn.getLeaseOwner(), turn.getLeaseId(), 0);
                }
                log.warn("Agent worker lost lease, workerId={}, turnId={}",
                    workerId, turn.getId(), error);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * 按固定间隔自动领取并执行任务。重复调用不会创建多个调度线程。
     */
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

    /**
     * @return 自动轮询线程是否已经启动且尚未关闭
     */
    public synchronized boolean isPolling() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /**
     * 停止自动轮询；正在进行的外部调用能否中断由其具体实现决定。
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (activePolls == 0) leaseScheduler.shutdownNow();
        if (activePolls == 0) turnExecutor.shutdownNow();
    }

    /**
     * 标记 Worker 正在轮询，拒绝同一实例并发或递归执行 poll。
     */
    private synchronized void beginPoll() {
        if (closed) throw new IllegalStateException("AgentWorker is already closed");
        if (activePolls > 0) {
            throw new IllegalStateException("AgentWorker poll is already in progress");
        }
        activePolls++;
    }

    /**
     * 清除轮询标志，保证异常路径结束后 Worker 仍可继续使用。
     */
    private synchronized void endPoll() {
        activePolls--;
        if (closed && activePolls == 0) {
            leaseScheduler.shutdownNow();
            turnExecutor.shutdownNow();
        }
    }
}

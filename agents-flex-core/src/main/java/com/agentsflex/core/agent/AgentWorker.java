/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.store.AgentRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 从 AgentRunStore 领取可执行任务并推进到终止或阻塞状态的 Worker。
 */
public final class AgentWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);

    private final String workerId;
    private final AgentRunner runner;
    private final long leaseMillis;
    private final AgentInvocationContextProvider invocationContextProvider;
    private ScheduledExecutorService scheduler;

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
    }

    /**
     * 从 Store 领取并同步执行一批任务。
     *
     * @param limit 本次最多领取的任务数
     * @return 已推进到终止或阻塞状态的运行列表
     */
    public List<AgentRun> pollAndRun(int limit) {
        // 先消费外部恢复命令，使被唤醒的 Run 进入可领取状态。
        runner.processCommands(workerId, leaseMillis, limit);
        long now = System.currentTimeMillis();
        List<AgentRunSnapshot> claimed = runner.getRunStore()
            .claimRunnable(workerId, now, leaseMillis, limit);
        List<AgentRun> results = new ArrayList<>(claimed.size());
        for (AgentRunSnapshot snapshot : claimed) {
            AgentRun run = null;
            try {
                run = runner.restore(snapshot.getRunId(),
                    invocationContextProvider.provide(snapshot));
                run.updateLease(workerId, snapshot.getLeaseUntil());
                run = runner.runLeased(run, workerId);
                runner.resumeParentFromChild(run);
            } finally {
                runner.getRunStore().releaseLease(snapshot.getRunId(), workerId);
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
    public AgentRunSnapshot renewLease(String runId) {
        return runner.getRunStore().renewLease(runId, workerId,
            System.currentTimeMillis() + leaseMillis);
    }

    /** 延长租约并同步更新正在执行的 AgentRun 版本。 */
    public AgentRunSnapshot renewLease(AgentRun run) {
        AgentRunSnapshot snapshot = renewLease(run.getId());
        run.updateVersion(snapshot.getVersion());
        run.updateLease(snapshot.getLeaseOwner(), snapshot.getLeaseUntil());
        return snapshot;
    }

    /** 按固定间隔自动领取并执行任务。重复调用不会创建多个调度线程。 */
    public synchronized void startPolling(long pollIntervalMillis, int batchSize) {
        if (pollIntervalMillis <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("pollIntervalMillis and batchSize must be greater than 0");
        }
        if (scheduler != null) {
            return;
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
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}

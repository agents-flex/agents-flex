/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import org.junit.Test;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** Explicit real-Redis benchmark; it is excluded from normal Surefire name patterns. */
public class RedisAgentTurnStoreBenchmark {
    @Test
    public void benchmarkLifecycle() throws Exception {
        int tasks = Integer.getInteger("store.benchmark.tasks", 2000);
        int workers = Integer.getInteger("store.benchmark.workers", 8);
        int reads = Integer.getInteger("store.benchmark.reads", tasks * 5);
        String prefix = "agents-flex-bench:agent:redis:" + UUID.randomUUID() + ":";
        RedisAgentStoreConfig config = RedisAgentStoreConfig.builder(
            System.getProperty("redis.test.uri", "redis://127.0.0.1:6379"))
            .keyPrefix(prefix).build();
        try {
            config.jedis().ping();
            RedisAgentTurnStore store = config.turnStore();
            String[] ids = new String[tasks];
            long[] create = new long[tasks];
            long start = System.nanoTime();
            for (int i = 0; i < tasks; i++) {
                ids[i] = "turn-" + i;
                long op = System.nanoTime();
                store.save(snapshot(ids[i]), -1);
                create[i] = System.nanoTime() - op;
            }
            report("redis agent create", tasks, System.nanoTime() - start, create);

            Result lifecycle = lifecycle(store, tasks, workers);
            assertEquals(tasks, lifecycle.count);
            report("redis agent claim+save", tasks, lifecycle.elapsed, lifecycle.latencies);

            Result load = loads(store, ids, reads, workers);
            report("redis agent load", reads, load.elapsed, load.latencies);
        } finally {
            cleanup(config, prefix);
            config.close();
        }
    }

    private Result lifecycle(RedisAgentTurnStore store, int tasks, int workers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        AtomicInteger completed = new AtomicInteger();
        long[] latencies = new long[tasks];
        Future<?>[] futures = new Future<?>[workers];
        long start = System.nanoTime();
        for (int i = 0; i < workers; i++) {
            final String worker = "worker-" + i;
            futures[i] = pool.submit(() -> {
                while (true) {
                    long claimStarted = System.nanoTime();
                    List<AgentTurnSnapshot> claimed = store.claimRunnable(
                        worker, store.currentTimeMillis(), 60_000, 20);
                    if (claimed.isEmpty()) break;
                    long claimPerTask = (System.nanoTime() - claimStarted) / claimed.size();
                    for (AgentTurnSnapshot value : claimed) {
                        long op = System.nanoTime();
                        AgentTurnSnapshot terminal = value.withState(value.getState().toBuilder()
                            .status(AgentTurnStatus.COMPLETED).build());
                        store.save(terminal, value.getState().getVersion());
                        store.releaseLease(value.getState().getTurnId(), worker,
                            value.getState().getLeaseId());
                        latencies[completed.getAndIncrement()] = claimPerTask + System.nanoTime() - op;
                    }
                }
            });
        }
        for (Future<?> future : futures) future.get();
        pool.shutdownNow();
        return new Result(completed.get(), System.nanoTime() - start, latencies);
    }

    private Result loads(RedisAgentTurnStore store, String[] ids, int reads, int workers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        AtomicInteger cursor = new AtomicInteger();
        long[] latencies = new long[reads];
        Future<?>[] futures = new Future<?>[workers];
        long start = System.nanoTime();
        for (int i = 0; i < workers; i++) futures[i] = pool.submit(() -> {
            int slot;
            while ((slot = cursor.getAndIncrement()) < reads) {
                long op = System.nanoTime();
                store.load(ids[slot % ids.length]);
                latencies[slot] = System.nanoTime() - op;
            }
        });
        for (Future<?> future : futures) future.get();
        pool.shutdownNow();
        return new Result(reads, System.nanoTime() - start, latencies);
    }

    private AgentTurnSnapshot snapshot(String id) {
        AgentTurnState state = AgentTurnState.builder(id, AgentExecutionPolicy.defaults(), 1)
            .status(AgentTurnStatus.READY).updatedAt(1).build();
        return AgentTurnSnapshot.of("benchmark", "1", state);
    }

    private void cleanup(RedisAgentStoreConfig config, String prefix) {
        String cursor = "0";
        do {
            ScanResult<String> scan = config.jedis().scan(cursor,
                new ScanParams().match(prefix + "*").count(500));
            cursor = scan.getCursor();
            if (!scan.getResult().isEmpty())
                config.jedis().del(scan.getResult().toArray(new String[0]));
        } while (!"0".equals(cursor));
    }

    private static void report(String name, int operations, long elapsed, long[] samples) {
        Arrays.sort(samples);
        System.out.printf("BENCH %-25s ops=%d throughput=%.1f ops/s p50=%.3fms p95=%.3fms p99=%.3fms%n",
            name, operations, operations * 1_000_000_000d / elapsed,
            percentile(samples, .50) / 1_000_000d, percentile(samples, .95) / 1_000_000d,
            percentile(samples, .99) / 1_000_000d);
    }

    private static long percentile(long[] values, double p) {
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * p) - 1)];
    }

    private static final class Result {
        private final int count;
        private final long elapsed;
        private final long[] latencies;

        private Result(int count, long elapsed, long[] latencies) {
            this.count = count;
            this.elapsed = elapsed;
            this.latencies = latencies;
        }
    }
}

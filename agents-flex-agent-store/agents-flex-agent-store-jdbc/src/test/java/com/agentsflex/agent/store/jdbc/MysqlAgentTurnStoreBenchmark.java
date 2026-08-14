/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/** Explicit real-MySQL benchmark; it is excluded from normal Surefire name patterns. */
public class MysqlAgentTurnStoreBenchmark {
    @Test
    public void benchmarkLifecycle() throws Exception {
        int tasks = Integer.getInteger("store.benchmark.tasks", 2000);
        int workers = Integer.getInteger("store.benchmark.workers", 8);
        int reads = Integer.getInteger("store.benchmark.reads", tasks * 5);
        String prefix = "bench_agent_" + UUID.randomUUID().toString().replace("-", "") + "_";
        HikariDataSource dataSource = dataSource(workers);
        JdbcAgentStoreConfig config = JdbcAgentStoreConfig.builder(dataSource)
            .tablePrefix(prefix).build();
        try {
            config.schema().initialize();
            JdbcAgentTurnStore store = config.turnStore();
            String[] ids = new String[tasks];
            long[] create = new long[tasks];
            long start = System.nanoTime();
            for (int i = 0; i < tasks; i++) {
                ids[i] = "turn-" + i;
                long op = System.nanoTime();
                store.save(snapshot(ids[i]), -1);
                create[i] = System.nanoTime() - op;
            }
            report("mysql agent create", tasks, System.nanoTime() - start, create);

            Result lifecycle = lifecycle(store, tasks, workers);
            assertEquals(tasks, lifecycle.count);
            report("mysql agent claim+save", tasks, lifecycle.elapsed, lifecycle.latencies);

            Result load = loads(store, ids, reads, workers);
            report("mysql agent load", reads, load.elapsed, load.latencies);
        } finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + prefix + "compression_states");
                statement.execute("DROP TABLE IF EXISTS " + prefix + "turns");
            }
            dataSource.close();
        }
    }

    private Result lifecycle(JdbcAgentTurnStore store, int tasks, int workers) throws Exception {
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

    private Result loads(JdbcAgentTurnStore store, String[] ids, int reads, int workers) throws Exception {
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

    private HikariDataSource dataSource(int workers) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(requiredProperty("mysql.test.url"));
        config.setUsername(System.getProperty("mysql.test.user", "root"));
        config.setPassword(requiredEnv("MYSQL_TEST_PASSWORD"));
        config.setMaximumPoolSize(workers + 2);
        config.setMinimumIdle(workers);
        return new HikariDataSource(config);
    }

    private AgentTurnSnapshot snapshot(String id) {
        AgentTurnState state = AgentTurnState.builder(id, AgentExecutionPolicy.defaults(), 1)
            .status(AgentTurnStatus.READY).rootTurnId(id).updatedAt(1).build();
        return AgentTurnSnapshot.of("benchmark", "1", state);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalStateException(name + " is required");
        return value;
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

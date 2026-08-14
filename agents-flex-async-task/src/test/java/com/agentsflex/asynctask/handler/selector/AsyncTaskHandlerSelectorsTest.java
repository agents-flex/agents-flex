/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.AsyncTaskOptions;
import com.agentsflex.asynctask.TaskQueryContext;
import com.agentsflex.asynctask.TaskQueryParams;
import com.agentsflex.asynctask.TaskQueryResult;
import com.agentsflex.asynctask.TaskSubmitContext;
import com.agentsflex.asynctask.TaskSubmitResult;
import com.agentsflex.asynctask.handler.AsyncTaskHandler;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/**
 * 验证所有内置 Handler Selector 的路由规则、线程安全状态边界和非法配置处理。
 */
public class AsyncTaskHandlerSelectorsTest {
    private final AsyncTaskHandler<String> a = handler("a");
    private final AsyncTaskHandler<String> b = handler("b");
    private final AsyncTaskHandler<String> c = handler("c");
    private final List<AsyncTaskHandler<?>> candidates = Arrays.asList(a, b, c);

    /**
     * 轮询必须严格遵循 Registry 给出的稳定顺序，并在末尾重新开始。
     */
    @Test
    public void shouldSelectRoundRobin() {
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.roundRobin();
        assertSame(a, selector.select(context(candidates)));
        assertSame(b, selector.select(context(candidates)));
        assertSame(c, selector.select(context(candidates)));
        assertSame(a, selector.select(context(candidates)));
        assertNull(selector.select(context(Collections.emptyList())));
        assertNotSame(selector, AsyncTaskHandlerSelectors.roundRobin());
    }

    /** 多线程共享同一轮询实例时不能丢失序号，每个候选获得的次数应完全相同。 */
    @Test
    public void shouldSelectRoundRobinConcurrently() throws Exception {
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.roundRobin();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                calls.add(() -> selector.select(context(candidates)).getKey());
            }
            Map<String, Integer> counts = new HashMap<>();
            for (Future<String> future : executor.invokeAll(calls)) {
                String key = future.get();
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            }
            assertEquals(Integer.valueOf(100), counts.get("a"));
            assertEquals(Integer.valueOf(100), counts.get("b"));
            assertEquals(Integer.valueOf(100), counts.get("c"));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 随机选择无法断言具体结果，但任何一次结果都必须属于候选集合。
     */
    @Test
    public void shouldSelectRandomCandidateOnly() {
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.random();
        for (int i = 0; i < 100; i++) assertTrue(candidates.contains(selector.select(context(candidates))));
        assertSame(a, selector.select(context(Collections.<AsyncTaskHandler<?>>singletonList(a))));
        assertNull(selector.select(context(Collections.emptyList())));
    }

    /**
     * 权重 2:1:1 的一个完整周期必须产生对应次数，并拒绝漏配或非法权重。
     */
    @Test
    public void shouldSelectByWeight() {
        Map<String, Integer> weights = new HashMap<>();
        weights.put("a", 2);
        weights.put("b", 1);
        weights.put("c", 1);
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.weighted(weights);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            String key = selector.select(context(candidates)).getKey();
            counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
        }
        assertEquals(Integer.valueOf(2), counts.get("a"));
        assertEquals(Integer.valueOf(1), counts.get("b"));
        assertEquals(Integer.valueOf(1), counts.get("c"));

        // 构造后修改调用方 Map 不得改变选择器内部权重。
        weights.clear();
        assertNotNull(selector.select(context(candidates)));
        assertNull(selector.select(context(Collections.emptyList())));

        expect(IllegalArgumentException.class, () -> AsyncTaskHandlerSelectors.weighted(null));
        expect(IllegalArgumentException.class, () -> AsyncTaskHandlerSelectors.weighted(Collections.emptyMap()));
        expect(IllegalArgumentException.class, () -> AsyncTaskHandlerSelectors.weighted(
            Collections.singletonMap("a", 0)));
        expect(IllegalArgumentException.class, () -> AsyncTaskHandlerSelectors.weighted(
            Collections.singletonMap(" ", 1)));
        Map<String, Integer> nullWeight = new HashMap<>();
        nullWeight.put("a", null);
        expect(IllegalArgumentException.class, () -> AsyncTaskHandlerSelectors.weighted(nullWeight));
        expect(IllegalStateException.class, () -> AsyncTaskHandlerSelectors.weighted(
            Collections.singletonMap("a", 1)).select(context(candidates)));
        Map<String, Integer> unknownWeights = new HashMap<>();
        unknownWeights.put("a", 1);
        unknownWeights.put("b", 1);
        unknownWeights.put("unknown", 1);
        expect(IllegalStateException.class,
            () -> AsyncTaskHandlerSelectors.weighted(unknownWeights).select(context(candidates)));
    }

    /**
     * 相同分片键和候选集合应始终选择同一个 Handler，空键则必须明确失败。
     */
    @Test
    public void shouldSelectByConsistentHash() {
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.consistentHash(
            context -> (String) context.getRequest());
        AsyncTaskHandler<?> selected = selector.select(context("tenant-1", candidates));
        for (int i = 0; i < 20; i++) assertSame(selected, selector.select(context("tenant-1", candidates)));
        List<AsyncTaskHandler<?>> reversed = Arrays.<AsyncTaskHandler<?>>asList(c, b, a);
        assertSame("Rendezvous Hash 不应依赖候选遍历顺序",
            selected, selector.select(context("tenant-1", reversed)));
        assertNull(selector.select(context("tenant-1", Collections.emptyList())));
        expect(IllegalArgumentException.class,
            () -> AsyncTaskHandlerSelectors.consistentHash(context -> " ").select(context(candidates)));
        expect(IllegalArgumentException.class, () -> AsyncTaskHandlerSelectors.consistentHash(null));
    }

    /**
     * 最少活跃选择最小计数，平局按稳定候选顺序处理，并拒绝无效的负计数。
     */
    @Test
    public void shouldSelectLeastActive() {
        Map<String, Long> active = new HashMap<>();
        active.put("a", 5L);
        active.put("b", 1L);
        active.put("c", 1L);
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.leastActive(active::get);
        assertSame(b, selector.select(context(candidates)));
        assertNull(selector.select(context(Collections.emptyList())));
        active.put("a", 1L);
        assertSame(a, selector.select(context(candidates)));
        active.put("a", -1L);
        expect(IllegalStateException.class, () -> selector.select(context(candidates)));
        expect(IllegalArgumentException.class, () -> AsyncTaskHandlerSelectors.leastActive(null));
    }

    /**
     * 选择上下文必须校验必填项并复制候选列表，避免 Registry 后续修改破坏当前选择过程。
     */
    @Test
    public void shouldValidateAndSnapshotSelectionContext() {
        AsyncTaskOptions options = new AsyncTaskOptions();
        List<AsyncTaskHandler<?>> mutable = new ArrayList<>(candidates);
        AsyncTaskHandlerSelectionContext context =
            new AsyncTaskHandlerSelectionContext("request", options, mutable);
        mutable.clear();

        assertEquals("request", context.getRequest());
        assertSame(options, context.getOptions());
        assertEquals(3, context.getCandidates().size());
        expect(UnsupportedOperationException.class, () -> context.getCandidates().clear());
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext(null, options, candidates));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext("request", null, candidates));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext("request", options, null));
    }

    private AsyncTaskHandlerSelectionContext context(List<AsyncTaskHandler<?>> handlers) {
        return context("request", handlers);
    }

    private AsyncTaskHandlerSelectionContext context(String request, List<AsyncTaskHandler<?>> handlers) {
        return new AsyncTaskHandlerSelectionContext(request, new AsyncTaskOptions(), handlers);
    }

    private AsyncTaskHandler<String> handler(String key) {
        return new AsyncTaskHandler<String>() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public Class<String> getSubmitParamsType() {
                return String.class;
            }

            @Override
            public TaskSubmitResult submit(String params, TaskSubmitContext context) {
                return null;
            }

            @Override
            public TaskQueryResult query(TaskQueryParams params, TaskQueryContext context) {
                return null;
            }
        };
    }

    private void expect(Class<? extends Throwable> type, Runnable runnable) {
        try {
            runnable.run();
            fail("Expected " + type.getName());
        } catch (Throwable error) {
            if (!type.isInstance(error)) throw error;
        }
    }
}

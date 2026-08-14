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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 不同 request 类型对应的候选集合必须分别轮询，交错提交不能相互消耗序号。 */
    @Test
    public void shouldIsolateRoundRobinSequenceByCandidateGroup() {
        AsyncTaskHandler<?> d = handler("d");
        AsyncTaskHandler<?> e = handler("e");
        List<AsyncTaskHandler<?>> firstGroup = Arrays.<AsyncTaskHandler<?>>asList(a, b);
        List<AsyncTaskHandler<?>> secondGroup = Arrays.asList(d, e);
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.roundRobin();

        assertSame(a, selector.select(context(firstGroup)));
        assertSame(d, selector.select(context(secondGroup)));
        assertSame(b, selector.select(context(firstGroup)));
        assertSame(e, selector.select(context(secondGroup)));
        assertSame(a, selector.select(context(firstGroup)));
    }

    /** Handler Key 即使包含候选组编码使用的分隔符，也不能让两个不同候选集合共享轮询序号。 */
    @Test
    public void shouldDistinguishCandidateGroupsWithDelimiterInHandlerKey() {
        AsyncTaskHandler<?> first = handler("a;b");
        AsyncTaskHandler<?> second = handler("c");
        AsyncTaskHandler<?> third = handler("a");
        AsyncTaskHandler<?> fourth = handler("b;c");
        List<AsyncTaskHandler<?>> firstGroup = Arrays.asList(first, second);
        List<AsyncTaskHandler<?>> secondGroup = Arrays.asList(third, fourth);
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.roundRobin();

        assertSame(first, selector.select(context(firstGroup)));
        assertSame(third, selector.select(context(secondGroup)));
        assertSame(second, selector.select(context(firstGroup)));
        assertSame(fourth, selector.select(context(secondGroup)));
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
     * Manager 共享一份 Weighted Selector 时，权重表可以覆盖多个候选组，各组必须独立推进权重周期。
     */
    @Test
    public void shouldSupportGlobalWeightsAndIsolateWeightedSequenceByCandidateGroup() {
        AsyncTaskHandler<?> d = handler("d");
        AsyncTaskHandler<?> e = handler("e");
        Map<String, Integer> weights = new HashMap<>();
        weights.put("a", 2);
        weights.put("b", 1);
        weights.put("d", 1);
        weights.put("e", 1);
        weights.put("unused-handler", 10);
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.weighted(weights);
        List<AsyncTaskHandler<?>> firstGroup = Arrays.<AsyncTaskHandler<?>>asList(a, b);
        List<AsyncTaskHandler<?>> secondGroup = Arrays.asList(d, e);

        assertSame(a, selector.select(context(firstGroup)));
        assertSame(d, selector.select(context(secondGroup)));
        assertSame(a, selector.select(context(firstGroup)));
        assertSame(e, selector.select(context(secondGroup)));
        assertSame(b, selector.select(context(firstGroup)));
    }

    /** 多线程执行完整权重周期时，原子序号不能丢失，最终比例必须精确符合配置。 */
    @Test
    public void shouldSelectByWeightConcurrently() throws Exception {
        Map<String, Integer> weights = new HashMap<>();
        weights.put("a", 2);
        weights.put("b", 1);
        weights.put("c", 1);
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.weighted(weights);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                calls.add(() -> selector.select(context(candidates)).getKey());
            }
            Map<String, Integer> counts = new HashMap<>();
            for (Future<String> future : executor.invokeAll(calls)) {
                String key = future.get();
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            }
            assertEquals(Integer.valueOf(200), counts.get("a"));
            assertEquals(Integer.valueOf(100), counts.get("b"));
            assertEquals(Integer.valueOf(100), counts.get("c"));
        } finally {
            executor.shutdownNow();
        }
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
        expect(IllegalArgumentException.class,
            () -> AsyncTaskHandlerSelectors.consistentHash(context -> null).select(context(candidates)));
        expect(IllegalArgumentException.class, () -> AsyncTaskHandlerSelectors.consistentHash(null));
    }

    /**
     * Rendezvous Hash 增删节点时只允许与该节点相关的业务键迁移，其他键必须保持原路由。
     */
    @Test
    public void shouldMinimizeRemappingWhenConsistentHashCandidatesChange() {
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.consistentHash(
            context -> (String) context.getRequest());
        List<AsyncTaskHandler<?>> original = Arrays.<AsyncTaskHandler<?>>asList(a, b);
        List<AsyncTaskHandler<?>> expanded = Arrays.<AsyncTaskHandler<?>>asList(a, b, c);
        int movedToNewHandler = 0;
        int unchanged = 0;

        for (int i = 0; i < 1000; i++) {
            String key = "tenant-中文-" + i;
            AsyncTaskHandler<?> before = selector.select(context(key, original));
            AsyncTaskHandler<?> afterAdd = selector.select(context(key, expanded));
            if (afterAdd == c) {
                movedToNewHandler++;
            } else {
                assertSame(before, afterAdd);
                unchanged++;
            }

            AsyncTaskHandler<?> afterRemove = selector.select(context(key, original));
            if (afterAdd != c) assertSame(afterAdd, afterRemove);
        }
        assertTrue("新增节点后应有部分业务键迁移到新节点", movedToNewHandler > 0);
        assertTrue("新增节点后不应迁移全部业务键", unchanged > 0);
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

    /** LeastActive 每轮只读取每个候选一次，并应原样传播负载数据源异常。 */
    @Test
    public void shouldReadEachActiveCountOnceAndPropagateProviderFailure() {
        Map<String, AtomicInteger> calls = new HashMap<>();
        calls.put("a", new AtomicInteger());
        calls.put("b", new AtomicInteger());
        calls.put("c", new AtomicInteger());
        AsyncTaskHandlerSelector selector = AsyncTaskHandlerSelectors.leastActive(key -> {
            calls.get(key).incrementAndGet();
            return key.equals("b") ? 0 : 1;
        });

        assertSame(b, selector.select(context(candidates)));
        assertEquals(1, calls.get("a").get());
        assertEquals(1, calls.get("b").get());
        assertEquals(1, calls.get("c").get());

        IllegalStateException failure = new IllegalStateException("metrics unavailable");
        try {
            AsyncTaskHandlerSelectors.leastActive(key -> { throw failure; }).select(context(candidates));
            fail("Expected active count provider failure");
        } catch (IllegalStateException actual) {
            assertSame(failure, actual);
        }
    }

    /**
     * 选择上下文必须校验必填项并复制候选列表，避免 Registry 后续修改破坏当前选择过程。
     */
    @Test
    public void shouldValidateAndSnapshotSelectionContext() {
        AsyncTaskOptions options = new AsyncTaskOptions();
        options.setHandlerKey("handler");
        options.setProviderKey("provider");
        options.setAccountId("account");
        options.setTenantId("tenant");
        options.setPriority(8);
        options.setDelayMillis(1200);
        options.setMetadata(Collections.<String, Object>singletonMap("trace", "t1"));
        List<AsyncTaskHandler<?>> mutable = new ArrayList<>(candidates);
        AsyncTaskHandlerSelectionContext context =
            new AsyncTaskHandlerSelectionContext("request", options, mutable);
        mutable.clear();

        assertEquals("request", context.getRequest());
        assertNotSame(options, context.getOptions());
        assertEquals("handler", context.getOptions().getHandlerKey());
        assertEquals("provider", context.getOptions().getProviderKey());
        assertEquals("account", context.getOptions().getAccountId());
        assertEquals("tenant", context.getOptions().getTenantId());
        assertEquals(8, context.getOptions().getPriority());
        assertEquals(1200, context.getOptions().getDelayMillis());
        assertEquals("t1", context.getOptions().getMetadata().get("trace"));
        expect(UnsupportedOperationException.class,
            () -> context.getOptions().getMetadata().put("trace", "selector-mutated"));
        context.getOptions().setTenantId("selector-mutated");
        assertEquals("tenant", options.getTenantId());
        options.setProviderKey("caller-mutated");
        assertEquals("provider", context.getOptions().getProviderKey());
        assertEquals(3, context.getCandidates().size());
        expect(UnsupportedOperationException.class, () -> context.getCandidates().clear());
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext(null, options, candidates));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext("request", null, candidates));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext("request", options, null));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext("request", options,
                Arrays.<AsyncTaskHandler<?>>asList(a, null)));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext("request", options,
                Arrays.<AsyncTaskHandler<?>>asList(a, handler("a"))));
        expect(IllegalArgumentException.class,
            () -> new AsyncTaskHandlerSelectionContext("request", options,
                Collections.<AsyncTaskHandler<?>>singletonList(handler(" "))));
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

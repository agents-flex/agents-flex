/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import java.util.Map;

/**
 * 快速创建内置 Handler Selector 的工厂。每次调用都会返回独立实例。
 */
public final class AsyncTaskHandlerSelectors {
    private AsyncTaskHandlerSelectors() {
    }

    /**
     * 创建一个线程安全的轮询选择器。
     */
    public static AsyncTaskHandlerSelector roundRobin() {
        return new RoundRobinAsyncTaskHandlerSelector();
    }

    /**
     * 创建一个使用线程本地随机数的均匀随机选择器。
     */
    public static AsyncTaskHandlerSelector random() {
        return new RandomAsyncTaskHandlerSelector();
    }

    /**
     * 创建按 Handler Key 整数权重分配请求的选择器。
     */
    public static AsyncTaskHandlerSelector weighted(Map<String, Integer> weights) {
        return new WeightedAsyncTaskHandlerSelector(weights);
    }

    /**
     * 创建使用业务分片键保持稳定路由的一致性哈希选择器。
     */
    public static AsyncTaskHandlerSelector consistentHash(AsyncTaskHandlerKeyExtractor keyExtractor) {
        return new ConsistentHashAsyncTaskHandlerSelector(keyExtractor);
    }

    /**
     * 创建优先选择当前活动任务数最少 Handler 的选择器。
     */
    public static AsyncTaskHandlerSelector leastActive(AsyncTaskHandlerActiveCountProvider provider) {
        return new LeastActiveAsyncTaskHandlerSelector(provider);
    }
}

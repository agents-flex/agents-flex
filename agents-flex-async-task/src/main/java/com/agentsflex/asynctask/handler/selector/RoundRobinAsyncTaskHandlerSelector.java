/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按候选列表稳定顺序循环选择 Handler 的线程安全实现。
 */
public final class RoundRobinAsyncTaskHandlerSelector implements AsyncTaskHandlerSelector {
    /**
     * 每个候选集合独立计数，避免 OCR 与视频等不同能力相互扰动轮询顺序。
     */
    private final ConcurrentMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    @Override
    public AsyncTaskHandler<?> select(AsyncTaskHandlerSelectionContext context) {
        List<AsyncTaskHandler<?>> candidates = context.getCandidates();
        if (candidates.isEmpty()) return null;
        String groupKey = AsyncTaskHandlerSelectorSupport.candidateGroupKey(candidates);
        long current = sequences.computeIfAbsent(groupKey, ignored -> new AtomicLong()).getAndIncrement();
        // 显式转为 long，确保编译器绑定 JDK 8 已提供的 floorMod(long, long)，避免匹配 JDK 9 的 long/int 重载。
        return candidates.get((int) Math.floorMod(current, (long) candidates.size()));
    }
}

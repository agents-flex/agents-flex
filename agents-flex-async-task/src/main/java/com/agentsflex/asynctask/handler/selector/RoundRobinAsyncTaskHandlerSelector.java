/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按候选列表稳定顺序循环选择 Handler 的线程安全实现。
 */
public final class RoundRobinAsyncTaskHandlerSelector implements AsyncTaskHandlerSelector {
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public AsyncTaskHandler<?> select(AsyncTaskHandlerSelectionContext context) {
        List<AsyncTaskHandler<?>> candidates = context.getCandidates();
        if (candidates.isEmpty()) return null;
        long current = sequence.getAndIncrement();
        return candidates.get((int) Math.floorMod(current, candidates.size()));
    }
}

/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;

/**
 * 选择活动任务数最少的 Handler；数量相同时使用候选列表中的第一个，保证结果可预测。
 */
public final class LeastActiveAsyncTaskHandlerSelector implements AsyncTaskHandlerSelector {
    private final AsyncTaskHandlerActiveCountProvider activeCountProvider;

    public LeastActiveAsyncTaskHandlerSelector(AsyncTaskHandlerActiveCountProvider activeCountProvider) {
        if (activeCountProvider == null) throw new IllegalArgumentException("activeCountProvider is required");
        this.activeCountProvider = activeCountProvider;
    }

    @Override
    public AsyncTaskHandler<?> select(AsyncTaskHandlerSelectionContext context) {
        AsyncTaskHandler<?> selected = null;
        long least = Long.MAX_VALUE;
        for (AsyncTaskHandler<?> candidate : context.getCandidates()) {
            long active = activeCountProvider.getActiveCount(candidate.getKey());
            if (active < 0) throw new IllegalStateException("Active count must not be negative: " + candidate.getKey());
            if (selected == null || active < least) {
                selected = candidate;
                least = active;
            }
        }
        return selected;
    }
}

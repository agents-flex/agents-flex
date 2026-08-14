/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按整数权重循环选择 Handler。
 *
 * <p>例如权重 a=2、b=1 时，连续三个选择会得到两个 a 和一个 b。所有候选都必须显式配置正权重，
 * 避免漏配的新 Handler 被意外分流。</p>
 */
public final class WeightedAsyncTaskHandlerSelector implements AsyncTaskHandlerSelector {
    private final Map<String, Integer> weights;
    private final AtomicLong sequence = new AtomicLong();

    public WeightedAsyncTaskHandlerSelector(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) throw new IllegalArgumentException("weights are required");
        Map<String, Integer> copy = new HashMap<>();
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()
                || entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("handler weight must use a non-empty key and be greater than 0");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        this.weights = Collections.unmodifiableMap(copy);
    }

    @Override
    public AsyncTaskHandler<?> select(AsyncTaskHandlerSelectionContext context) {
        List<AsyncTaskHandler<?>> candidates = context.getCandidates();
        if (candidates.isEmpty()) return null;
        if (weights.size() != candidates.size()) {
            throw new IllegalStateException("Handler weights must exactly match the candidate handlers");
        }
        long total = 0;
        for (AsyncTaskHandler<?> candidate : candidates) total = Math.addExact(total, weight(candidate));
        long point = Math.floorMod(sequence.getAndIncrement(), total);
        for (AsyncTaskHandler<?> candidate : candidates) {
            point -= weight(candidate);
            if (point < 0) return candidate;
        }
        throw new IllegalStateException("Unable to select an async task handler by weight");
    }

    private int weight(AsyncTaskHandler<?> handler) {
        Integer weight = weights.get(handler.getKey());
        if (weight == null)
            throw new IllegalStateException("Missing weight for async task handler: " + handler.getKey());
        return weight;
    }
}

/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 根据稳定业务键选择 Handler，使相同键在候选集合不变时始终路由到同一实现。
 */
public final class ConsistentHashAsyncTaskHandlerSelector implements AsyncTaskHandlerSelector {
    private final AsyncTaskHandlerKeyExtractor keyExtractor;

    public ConsistentHashAsyncTaskHandlerSelector(AsyncTaskHandlerKeyExtractor keyExtractor) {
        if (keyExtractor == null) throw new IllegalArgumentException("keyExtractor is required");
        this.keyExtractor = keyExtractor;
    }

    @Override
    public AsyncTaskHandler<?> select(AsyncTaskHandlerSelectionContext context) {
        List<AsyncTaskHandler<?>> candidates = context.getCandidates();
        if (candidates.isEmpty()) return null;
        String key = keyExtractor.extract(context);
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Consistent hash selection key is required");
        }
        AsyncTaskHandler<?> selected = null;
        long bestScore = Long.MIN_VALUE;
        // Rendezvous Hash 在增加或移除 Handler 时只迁移必要的键，且无需维护虚拟节点环。
        for (AsyncTaskHandler<?> candidate : candidates) {
            // 长度前缀避免业务键或 Handler Key 包含分隔符时产生拼接歧义。
            long score = hash(key.length() + ":" + key + candidate.getKey().length() + ":" + candidate.getKey());
            if (selected == null || Long.compareUnsigned(score, bestScore) > 0) {
                selected = candidate;
                bestScore = score;
            }
        }
        return selected;
    }

    private long hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < 8; i++) result = (result << 8) | (bytes[i] & 0xffL);
            return result;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}

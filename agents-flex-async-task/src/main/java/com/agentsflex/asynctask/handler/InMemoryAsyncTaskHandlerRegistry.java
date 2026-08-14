/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.asynctask.handler;

import com.agentsflex.asynctask.*;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 线程安全的进程内 Handler 注册表，适合固定配置和单 JVM 应用。
 */
public final class InMemoryAsyncTaskHandlerRegistry implements AsyncTaskHandlerRegistry {
    private final ConcurrentMap<String, AsyncTaskHandler<?>> handlers = new ConcurrentHashMap<>();

    /**
     * 原子注册 Handler，不允许后注册实现静默覆盖同名实现。
     *
     * @param handler 待注册的非空 Handler
     * @return 当前注册表，便于链式注册
     * @throws IllegalArgumentException Handler 或 key 为空
     * @throws IllegalStateException    key 已被占用
     */
    public InMemoryAsyncTaskHandlerRegistry register(AsyncTaskHandler<?> handler) {
        if (handler == null || handler.getKey() == null || handler.getKey().trim().isEmpty()
            || handler.getSubmitParamsType() == null) {
            throw new IllegalArgumentException("handler, handler.key and handler.submitParamsType are required");
        }
        AsyncTaskHandler<?> previous = handlers.putIfAbsent(handler.getKey(), handler);
        if (previous != null)
            throw new IllegalStateException("Async task handler already registered: " + handler.getKey());
        return this;
    }

    @Override
    public AsyncTaskHandler<?> get(String key) {
        AsyncTaskHandler<?> handler = handlers.get(key);
        if (handler == null) throw new IllegalStateException("Async task handler not found: " + key);
        return handler;
    }

    @Override
    public List<AsyncTaskHandler<?>> findBySubmitParamsType(Class<?> submitParamsType) {
        if (submitParamsType == null) throw new IllegalArgumentException("submitParamsType is required");
        List<AsyncTaskHandler<?>> matches = new ArrayList<>();
        for (AsyncTaskHandler<?> handler : handlers.values()) {
            if (submitParamsType.equals(handler.getSubmitParamsType())) matches.add(handler);
        }
        // ConcurrentHashMap 没有稳定遍历顺序，统一按 key 排序后再交给选择器。
        Collections.sort(matches, Comparator.comparing(AsyncTaskHandler::getKey));
        return Collections.unmodifiableList(matches);
    }
}

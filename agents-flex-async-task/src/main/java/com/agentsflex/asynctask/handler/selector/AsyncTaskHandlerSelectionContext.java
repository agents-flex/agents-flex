/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.AsyncTaskOptions;
import com.agentsflex.asynctask.handler.AsyncTaskHandler;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handler 选择时使用的隔离上下文。
 *
 * <p>上下文只在创建框架任务时存在，选择结果会作为 handlerKey 持久化，Worker 恢复任务时不会再次选择。
 * request 可用于租户、区域或模型等业务路由；options 是独立快照，Selector 对它的修改不会影响最终任务；
 * candidates 是不可修改且经过合法性校验的快照。</p>
 */
public final class AsyncTaskHandlerSelectionContext {
    private final Object request;
    private final AsyncTaskOptions options;
    private final List<AsyncTaskHandler<?>> candidates;

    public AsyncTaskHandlerSelectionContext(Object request, AsyncTaskOptions options,
                                            List<AsyncTaskHandler<?>> candidates) {
        if (request == null || options == null || candidates == null) {
            throw new IllegalArgumentException("request, options and candidates are required");
        }
        List<AsyncTaskHandler<?>> candidateSnapshot = new ArrayList<>(candidates);
        Set<String> keys = new HashSet<>();
        for (AsyncTaskHandler<?> candidate : candidateSnapshot) {
            if (candidate == null || candidate.getKey() == null || candidate.getKey().trim().isEmpty()) {
                throw new IllegalArgumentException("candidate handler and handler.key are required");
            }
            if (!keys.add(candidate.getKey())) {
                throw new IllegalArgumentException("duplicate candidate handler key: " + candidate.getKey());
            }
        }
        this.request = request;
        this.options = copyOptions(options);
        this.candidates = Collections.unmodifiableList(candidateSnapshot);
    }

    public Object getRequest() {
        return request;
    }

    public AsyncTaskOptions getOptions() {
        return options;
    }

    public List<AsyncTaskHandler<?>> getCandidates() {
        return candidates;
    }

    private AsyncTaskOptions copyOptions(AsyncTaskOptions source) {
        AsyncTaskOptions copy = new AsyncTaskOptions();
        copy.setHandlerKey(source.getHandlerKey());
        copy.setProviderKey(source.getProviderKey());
        copy.setAccountId(source.getAccountId());
        copy.setTenantId(source.getTenantId());
        copy.setPriority(source.getPriority());
        copy.setDelayMillis(source.getDelayMillis());
        copy.setMetadata(source.getMetadata());
        return copy;
    }
}

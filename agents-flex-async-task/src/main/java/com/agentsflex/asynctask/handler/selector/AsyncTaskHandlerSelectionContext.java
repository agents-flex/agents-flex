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
import java.util.List;

/**
 * Handler 选择时使用的只读上下文。
 *
 * <p>上下文只在创建框架任务时存在，选择结果会作为 handlerKey 持久化，Worker 恢复任务时不会再次选择。
 * request 可用于租户、区域或模型等业务路由；options 提供账号、租户和 metadata 等调度信息。</p>
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
        this.request = request;
        this.options = options;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
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
}

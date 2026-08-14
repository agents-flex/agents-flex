/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 使用线程本地随机数均匀选择候选 Handler。
 */
public final class RandomAsyncTaskHandlerSelector implements AsyncTaskHandlerSelector {
    @Override
    public AsyncTaskHandler<?> select(AsyncTaskHandlerSelectionContext context) {
        List<AsyncTaskHandler<?>> candidates = context.getCandidates();
        return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
}

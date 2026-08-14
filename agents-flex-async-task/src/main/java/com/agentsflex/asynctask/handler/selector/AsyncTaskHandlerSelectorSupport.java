/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Selector 内部共享的候选组标识工具。
 */
final class AsyncTaskHandlerSelectorSupport {
    private AsyncTaskHandlerSelectorSupport() {
    }

    /**
     * 使用排序后的 Handler Key 构造无歧义组标识，使同一候选集合共享自己的选择序号。
     */
    static String candidateGroupKey(List<AsyncTaskHandler<?>> candidates) {
        List<String> keys = new ArrayList<>(candidates.size());
        for (AsyncTaskHandler<?> candidate : candidates) keys.add(candidate.getKey());
        Collections.sort(keys);
        StringBuilder groupKey = new StringBuilder();
        for (String key : keys) groupKey.append(key.length()).append(':').append(key).append(';');
        return groupKey.toString();
    }
}

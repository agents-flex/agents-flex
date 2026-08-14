/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

/**
 * 从选择上下文提取稳定的分片键，供一致性哈希选择器使用。
 */
public interface AsyncTaskHandlerKeyExtractor {
    String extract(AsyncTaskHandlerSelectionContext context);
}

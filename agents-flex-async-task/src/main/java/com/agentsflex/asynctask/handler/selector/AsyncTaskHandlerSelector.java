/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

import com.agentsflex.asynctask.handler.AsyncTaskHandler;

/**
 * 在多个同类型 Handler 之间选择一个实际消费者。
 *
 * <p>实现可能带有轮询计数等进程内状态，因此应作为 Manager 的长期共享实例使用。返回值必须来自
 * context.candidates；返回 null 或其他 Handler 会由 Manager 拒绝。</p>
 */
public interface AsyncTaskHandlerSelector {
    AsyncTaskHandler<?> select(AsyncTaskHandlerSelectionContext context);
}

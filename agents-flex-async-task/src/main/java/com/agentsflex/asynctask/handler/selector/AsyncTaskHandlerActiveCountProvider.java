/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package com.agentsflex.asynctask.handler.selector;

/**
 * 提供 Handler 当前活动任务数，供最少活跃选择器使用。
 *
 * <p>实现可以读取本机计数或共享监控数据。集群部署若只读取本机计数，选择结果仅代表当前进程负载。</p>
 */
public interface AsyncTaskHandlerActiveCountProvider {
    long getActiveCount(String handlerKey);
}

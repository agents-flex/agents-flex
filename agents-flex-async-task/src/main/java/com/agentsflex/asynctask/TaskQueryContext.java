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
package com.agentsflex.asynctask;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 单次供应商查询的只读运行上下文。
 *
 * <p>它描述框架任务、重试计数和时间边界，不包含 TaskQueryParams，避免持久化查询标识与
 * 瞬时运行信息重复或产生两个事实来源。</p>
 */
public final class TaskQueryContext {
    private final String taskId;
    private final int queryCount;
    private final int consecutiveErrors;
    private final long createdAt;
    private final long deadlineAt;
    private final long currentTimeMillis;
    private final Map<String, Object> metadata;

    /**
     * 创建一次查询调用的上下文快照。
     *
     * @param taskId            框架任务 id
     * @param queryCount        本次调用前已经完成的查询次数
     * @param consecutiveErrors 本次调用前连续发生的查询异常次数
     * @param createdAt         任务创建的 Store 时间
     * @param deadlineAt        停止自动跟踪的绝对 Store 时间
     * @param currentTimeMillis 开始本次查询时的 Store 时间
     * @param metadata          任务扩展信息，构造器会防御性复制并暴露只读视图
     */
    public TaskQueryContext(String taskId, int queryCount, int consecutiveErrors,
                            long createdAt, long deadlineAt, long currentTimeMillis,
                            Map<String, Object> metadata) {
        this.taskId = taskId;
        this.queryCount = queryCount;
        this.consecutiveErrors = consecutiveErrors;
        this.createdAt = createdAt;
        this.deadlineAt = deadlineAt;
        this.currentTimeMillis = currentTimeMillis;
        this.metadata = metadata == null ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    public String getTaskId() {
        return taskId;
    }

    public int getQueryCount() {
        return queryCount;
    }

    public int getConsecutiveErrors() {
        return consecutiveErrors;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getDeadlineAt() {
        return deadlineAt;
    }

    public long getCurrentTimeMillis() {
        return currentTimeMillis;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}

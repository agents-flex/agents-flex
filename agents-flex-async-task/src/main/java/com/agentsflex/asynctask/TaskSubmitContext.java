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
 * 单次供应商提交的只读框架上下文。
 *
 * <p>taskId 同时作为默认幂等键，Handler 应尽量传给供应商，从而在网络结果未知时降低重复创建风险。</p>
 */
public final class TaskSubmitContext {
    private final String taskId;
    private final long currentTimeMillis;
    private final Map<String, Object> metadata;

    /**
     * 创建提交上下文。
     *
     * @param taskId            框架任务 id，同时作为默认幂等键
     * @param currentTimeMillis 开始本次提交时读取的 Store 权威时间
     * @param metadata          任务创建时保存的扩展信息；构造器会防御性复制
     */
    public TaskSubmitContext(String taskId, long currentTimeMillis, Map<String, Object> metadata) {
        this.taskId = taskId;
        this.currentTimeMillis = currentTimeMillis;
        this.metadata = metadata == null ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /**
     * 返回框架任务 id，不是供应商 externalTaskId。
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * 返回默认幂等键；当前与 taskId 相同，供应商支持时应随提交请求传递。
     */
    public String getIdempotencyKey() {
        return taskId;
    }

    public long getCurrentTimeMillis() {
        return currentTimeMillis;
    }

    /**
     * 返回只读 metadata；其中不包含提交参数本身。
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }
}

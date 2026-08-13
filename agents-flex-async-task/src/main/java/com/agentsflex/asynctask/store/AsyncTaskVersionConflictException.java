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
package com.agentsflex.asynctask.store;


/**
 * CAS 保存时当前版本与调用方期望版本不一致，表示任务已被其他线程或 Worker 更新。
 */
public class AsyncTaskVersionConflictException extends RuntimeException {
    /**
     * 创建包含任务 id、期望版本和实际版本的冲突异常。
     */
    public AsyncTaskVersionConflictException(String taskId, long expected, long actual) {
        super("Async task version conflict, taskId=" + taskId
            + ", expected=" + expected + ", actual=" + actual);
    }
}

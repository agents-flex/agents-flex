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
package com.agentsflex.asynctask.handler;


/**
 * 根据持久化的 handlerKey 找回供应商适配器的注册表。
 */
public interface AsyncTaskHandlerRegistry {
    /**
     * 获取指定 Handler；任务恢复后必须仍能使用相同 key 找到兼容实现。
     *
     * @param key {@link AsyncTaskHandler#getKey()} 返回的注册键
     * @return 已注册 Handler
     * @throws IllegalStateException key 未注册时抛出，避免任务被错误适配器处理
     */
    AsyncTaskHandler<?> get(String key);
}

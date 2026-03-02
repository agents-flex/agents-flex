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
package com.agentsflex.core.util;

/**
 * 表示对象支持创建一个独立副本（深拷贝语义）。
 * 修改副本不应影响原对象，反之亦然。
 *
 * @param <T> 副本的具体类型，通常为当前类自身
 */
public interface Copyable<T> {
    /**
     * 创建并返回当前对象的副本。
     *
     * @return 一个新的、内容相同但内存独立的对象
     */
    T copy();
}

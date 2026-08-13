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

import com.agentsflex.asynctask.*;
import com.agentsflex.asynctask.policy.AsyncTaskAdmissionPolicy;


import java.io.Serializable;

/**
 * 异步任务 Store 的二进制序列化扩展点。
 *
 * <p>JDBC 与 Redis Store 只依赖这个小接口，因此业务可以替换默认 JSONB 格式，
 * 例如接入加密、压缩或公司统一的序列化协议。</p>
 */
public interface AsyncTaskStoreSerializer {
    /**
     * 将任务快照编码为可持久化的独立字节数组。
     *
     * @param value 可序列化任务或 Store 值对象
     * @return 不依赖输入对象后续变化的字节数组
     * @throws IllegalArgumentException 对象包含不受支持或不可序列化的值
     */
    byte[] serialize(Serializable value);

    /**
     * 将受信任的持久化内容恢复成目标类型。
     *
     * @param bytes Store 读取的二进制内容；允许为 null
     * @param type  目标类型，不能为 null
     * @return 恢复后的对象；bytes 为 null 时返回 null
     * @throws IllegalStateException 内容损坏、类型不匹配或类型不在安全白名单
     */
    <T> T deserialize(byte[] bytes, Class<T> type);
}

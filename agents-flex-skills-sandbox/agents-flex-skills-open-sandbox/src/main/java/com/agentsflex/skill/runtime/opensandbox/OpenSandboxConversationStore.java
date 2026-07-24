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
package com.agentsflex.skill.runtime.opensandbox;

/** OpenSandbox 会话状态存储。 */
public interface OpenSandboxConversationStore {

    /** 查询会话记录；不存在时返回 {@code null}。 */
    OpenSandboxConversationRecord get(OpenSandboxConversationKey key);

    /**
     * 仅在会话记录不存在时创建。
     *
     * @return 创建成功返回 {@code true}，主键已存在返回 {@code false}
     */
    boolean create(OpenSandboxConversationRecord record);

    /** 更新已存在的会话记录。 */
    void update(OpenSandboxConversationRecord record);

    /** 删除并返回原会话记录；不存在时返回 {@code null}。 */
    OpenSandboxConversationRecord delete(OpenSandboxConversationKey key);
}

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
package com.agentsflex.core.memory;

import com.agentsflex.core.message.Message;

import java.util.Collection;
import java.util.List;

/**
 * 保存一个业务会话的完整消息时间线。
 *
 * <p>时间线既可以包含发送给模型的 UserMessage、AiMessage 和 ToolMessage，也可以包含
 * {@code modelVisible=false} 的页面状态消息。页面通常调用 {@link #getMessages(int)}，模型 Prompt
 * 调用 {@link #getModelMessages(int)}，从而让两类消息共用顺序和存储，又不会把审批按钮等 UI 数据
 * 发送给模型。</p>
 *
 * <p>实现类必须根据实际存储方式实现全部读写操作。需要与 AgentRunner 的可选会话投影集成时，
 * 持久化实现应高效实现窗口读取和按 ID 查询，并原子实现 {@link #addMessageIfAbsent(Message)} 和
 * {@link #updateMessage(Message, long)}。</p>
 */
public interface ChatMemory extends Memory {

    List<Message> getMessages(int count);

    /**
     * 从时间线末尾开始读取一个消息窗口，返回值仍按从旧到新的顺序排列。
     *
     * <p>{@code offset=0} 表示最新的消息窗口，{@code offset=20} 表示跳过最新 20 条后继续向前读取。
     * 数据库实现应使用分页或滑动窗口查询，避免将整个会话加载到内存。</p>
     *
     * @param offset 从最新消息向前跳过的数量
     * @param count  最大返回数量
     */
    List<Message> getMessages(int offset, int count);

    /**
     * 返回可发送给模型的消息视图。
     *
     * <p>实现应过滤 UI-only 消息，并返回最近最多 {@code count} 条按从旧到新排列的模型可见消息，
     * 使页面消息不会挤占模型上下文窗口。</p>
     */
    List<Message> getModelMessages(int count);

    void addMessage(Message message);

    /**
     * 按稳定消息 ID 查询一条消息。
     *
     * <p>持久化实现应使用主键或索引查询。</p>
     */
    Message getMessage(String messageId);

    /**
     * 按 messageId 幂等追加消息。
     *
     * @return 新增成功返回 true，消息已存在返回 false
     */
    boolean addMessageIfAbsent(Message message);

    /**
     * 使用乐观版本替换已有消息，只用于审批卡片等受控状态转换。
     *
     * <p>实现应使用 {@code messageId + expectedVersion} 作为更新条件，并在成功后把消息版本递增。
     * 找不到消息或版本不匹配时返回 false，不应覆盖较新的状态。</p>
     *
     * @return 更新成功返回 true，不存在或版本冲突返回 false
     */
    boolean updateMessage(Message message, long expectedVersion);

    void addMessages(Collection<? extends Message> messages);

    /** 清空整个会话；AgentRunner 不会调用该管理操作。 */
    void clear();
}
